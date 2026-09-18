import asyncio
import sqlite3
from datetime import datetime
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from newsclaw.api.routes.memory import router as memory_router
from newsclaw.memory.manager import MemoryManager
from newsclaw.memory.relational.types import MemoryNode, NodeType
from newsclaw.memory.storage import MemoryStorage
from newsclaw.memory.types import MemoryPriority, MemoryType, SemanticMemory


def _manager(tmp_path) -> MemoryManager:
    return MemoryManager(
        data_dir=tmp_path / "memory",
        memory_md_path=tmp_path / "MEMORY.md",
        search_backend="fts5",
    )


def _memory(content: str, *, subject: str = "", predicate: str = "") -> SemanticMemory:
    return SemanticMemory(
        type=MemoryType.FACT,
        priority=MemoryPriority.LONG_TERM,
        content=content,
        subject=subject,
        predicate=predicate,
        importance_score=0.8,
    )


def _memory_client(manager: MemoryManager) -> TestClient:
    app = FastAPI()
    app.include_router(memory_router)
    app.state.agent = SimpleNamespace(memory_manager=manager)
    return TestClient(app)


def test_v3_migration_backs_up_and_quarantines_legacy_desktop_memory(tmp_path):
    db_path = tmp_path / "old" / "newsclaw.db"
    db_path.parent.mkdir(parents=True)
    now = datetime.now().isoformat()
    with sqlite3.connect(str(db_path)) as conn:
        conn.execute("CREATE TABLE _schema_meta (key TEXT PRIMARY KEY, value TEXT)")
        conn.execute("INSERT INTO _schema_meta VALUES ('version', '2')")
        conn.execute(
            """
            CREATE TABLE memories (
                id TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                type TEXT NOT NULL DEFAULT 'fact',
                priority TEXT NOT NULL DEFAULT 'long_term',
                source TEXT DEFAULT '',
                importance_score REAL DEFAULT 0.5,
                access_count INTEGER DEFAULT 0,
                tags TEXT DEFAULT '[]',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                expires_at TEXT,
                metadata TEXT DEFAULT '{}',
                subject TEXT DEFAULT '',
                predicate TEXT DEFAULT '',
                confidence REAL DEFAULT 0.5,
                decay_rate REAL DEFAULT 0.1,
                last_accessed_at TEXT,
                superseded_by TEXT,
                source_episode_id TEXT,
                scope TEXT DEFAULT 'global',
                scope_owner TEXT DEFAULT '',
                agent_id TEXT DEFAULT ''
            )
            """
        )
        conn.execute(
            """
            INSERT INTO memories
            (id, content, type, priority, created_at, updated_at, scope, scope_owner)
            VALUES ('legacy-1', 'legacy desktop memory', 'fact', 'long_term', ?, ?, 'global', '')
            """,
            (now, now),
        )
        conn.commit()

    storage = MemoryStorage(db_path)

    rows = storage.load_all(scope="legacy_quarantine", scope_owner="", user_id="legacy")
    assert [row["content"] for row in rows] == ["legacy desktop memory"]
    # Backup filename uses target schema version, so v2 dbs upgrade
    # through to the current ``_SCHEMA_VERSION`` (was v4, bumped to v5
    # by v1.27.15 S2 P1-6 for ``conversation_turns.metadata``).
    from newsclaw.memory.storage import _SCHEMA_VERSION

    assert list(db_path.parent.glob(f"newsclaw.db.bak.v2_to_v{_SCHEMA_VERSION}.*"))


def test_two_users_do_not_see_each_other_long_term_memory(tmp_path):
    manager = _manager(tmp_path)

    manager.start_session("session-a", user_id="user-a")
    manager.add_memory(_memory("用户住在苏州"), scope="global")

    manager.start_session("session-b", user_id="user-b")
    manager.add_memory(_memory("用户住在上海"), scope="global")

    user_b_results = manager.search_memories("用户住在", scope="user")
    assert [m.content for m in user_b_results] == ["用户住在上海"]

    manager.start_session("session-a", user_id="user-a")
    user_a_results = manager.search_memories("用户住在", scope="user")
    assert [m.content for m in user_a_results] == ["用户住在苏州"]


def test_same_user_different_bot_workspaces_do_not_share_memory(tmp_path):
    manager = _manager(tmp_path)

    manager.start_session("writer-session", user_id="user-a", workspace_id="feishu:writer")
    manager.add_memory(_memory("用户喜欢写长文"), scope="global")

    manager.start_session("reviewer-session", user_id="user-a", workspace_id="feishu:reviewer")
    manager.add_memory(_memory("用户喜欢严格审稿"), scope="global")

    reviewer_results = manager.search_memories("用户喜欢", scope="user")
    assert [m.content for m in reviewer_results] == ["用户喜欢严格审稿"]

    manager.start_session("writer-session", user_id="user-a", workspace_id="feishu:writer")
    writer_results = manager.search_memories("用户喜欢", scope="user")
    assert [m.content for m in writer_results] == ["用户喜欢写长文"]


@pytest.mark.asyncio
async def test_memory_scope_context_is_task_local(tmp_path):
    manager = _manager(tmp_path)

    async def run_session(session_id: str, workspace_id: str):
        manager.start_session(session_id, user_id="user-a", workspace_id=workspace_id)
        await asyncio.sleep(0)
        return (
            manager._current_session_id,
            manager._current_user_id,
            manager._current_workspace_id,
        )

    writer, reviewer = await asyncio.gather(
        run_session("writer-session", "feishu:writer"),
        run_session("reviewer-session", "feishu:reviewer"),
    )

    assert writer == ("writer-session", "user-a", "feishu:writer")
    assert reviewer == ("reviewer-session", "user-a", "feishu:reviewer")


def test_legacy_quarantine_is_not_in_default_retrieval(tmp_path):
    manager = _manager(tmp_path)
    manager.store.save_semantic(
        _memory("用户住在上海"),
        scope="legacy_quarantine",
        user_id="legacy",
    )
    manager.start_session("session-a", user_id="user-a")
    manager.add_memory(_memory("用户住在苏州"), scope="global")

    results = manager.search_visible_semantic("用户住在", limit=5)
    context = "\n".join(m.content for m in results)

    assert "用户住在苏州" in context
    assert "用户住在上海" not in context


@pytest.mark.asyncio
async def test_same_user_subject_predicate_update_replaces_active_fact(tmp_path):
    manager = _manager(tmp_path)
    manager.start_session("session-a", user_id="user-a")

    first_id = await manager._save_extracted_item(
        {
            "type": "FACT",
            "content": "用户年龄是 28 岁",
            "subject": "用户",
            "predicate": "年龄",
            "importance": 0.8,
        }
    )
    second_id = await manager._save_extracted_item(
        {
            "type": "FACT",
            "content": "用户年龄是 29 岁",
            "subject": "用户",
            "predicate": "年龄",
            "importance": 0.8,
        }
    )

    assert first_id != second_id
    old = manager.store.get_semantic(first_id, include_inactive=True)
    saved = manager.store.get_semantic(second_id)
    assert old is not None
    assert old.superseded_by == second_id
    assert saved is not None
    assert saved.content == "用户年龄是 29 岁"

    active = manager.search_memories("用户年龄", scope="user")
    assert [m.content for m in active] == ["用户年龄是 29 岁"]


def test_explicit_none_user_id_does_not_reuse_previous_user(tmp_path):
    manager = _manager(tmp_path)

    manager.start_session("session-a", user_id="user-a")
    manager.add_memory(_memory("用户住在苏州"), scope="global")
    manager.start_session("session-anon", user_id=None)
    manager.add_memory(_memory("匿名用户住在杭州"), scope="global")

    anon_results = manager.search_memories("住在", scope="user")
    assert [m.content for m in anon_results] == ["匿名用户住在杭州"]

    manager.start_session("session-a2", user_id="user-a")
    user_results = manager.search_memories("住在", scope="user")
    assert [m.content for m in user_results] == ["用户住在苏州"]



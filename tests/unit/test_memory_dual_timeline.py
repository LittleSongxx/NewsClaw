"""双时间线（schema v7）与写入裁决（Mem0 式 SUPERSEDES）的行为钉子。"""

from __future__ import annotations

from datetime import datetime, timedelta
from pathlib import Path

import pytest

from newsclaw.config import settings
from newsclaw.memory.manager import MemoryManager
from newsclaw.memory.storage import MemoryStorage


@pytest.fixture(autouse=True)
def isolated(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "project_root", tmp_path)
    return tmp_path


def test_v7_columns_roundtrip(isolated):
    store = MemoryStorage(isolated / "m.db")
    occurred = "2026-08-01T10:00:00"
    store.save_memory(
        {"id": "f1", "content": "他上个月住在苏州", "type": "FACT",
         "priority": "LONG_TERM", "occurred_at": occurred, "valid_until": None}
    )
    got = store.get_memory("f1")
    assert got["occurred_at"] == occurred
    assert got["valid_until"] is None


def test_valid_until_soft_invalidates(isolated):
    store = MemoryStorage(isolated / "m.db")
    store.save_memory({"id": "old", "content": "用户 32 岁", "type": "FACT", "priority": "LONG_TERM"})
    store.save_memory({"id": "new", "content": "用户 33 岁", "type": "FACT", "priority": "LONG_TERM"})
    expired = (datetime.now() - timedelta(days=1)).isoformat()
    store.update_memory("old", {"valid_until": expired})
    visible = [m["id"] for m in store.load_all(active_only=True)]
    assert "new" in visible
    assert "old" not in visible


def test_valid_until_null_stays_active(isolated):
    store = MemoryStorage(isolated / "m.db")
    store.save_memory({"id": "k", "content": "用户是素食者", "type": "FACT", "priority": "LONG_TERM"})
    visible = [m["id"] for m in store.load_all(active_only=True)]
    assert "k" in visible


@pytest.mark.asyncio
async def test_supersedes_verdict_soft_invalidates_old_fact(isolated):
    """裁决为 SUPERSEDES 时：旧事实软失效（valid_until），新记忆独立落盘。"""
    from unittest.mock import patch

    from newsclaw.memory.types import MemoryPriority, MemoryType, SemanticMemory

    mm = MemoryManager(
        data_dir=isolated / "memory",
        memory_md_path=isolated / "MEMORY.md",
        search_backend="fts5",
    )
    seed = SemanticMemory(
        id="age28",
        content="用户长期居住在苏州工业园区附近",
        type=MemoryType.FACT,
        priority=MemoryPriority.LONG_TERM,
    )
    mm.store.save_semantic(seed)

    async def fake_verdict(new: str, old: str) -> str:
        return "SUPERSEDES"

    mm._conflict_verdict_with_llm = fake_verdict
    # FTS 对整句 CJK 的召回依赖分词；单测直接把种子喂给 L2 搜索，
    # 聚焦验证「likely → SUPERSEDES → 软失效」的裁决接线本身。
    with patch.object(
        mm.store, "search_semantic", return_value=[seed]
    ):
        item = {
            "type": "fact",
            "content": "用户已搬到上海，不再住在苏州工业园区",
            "importance": 0.8,
            "subject": "用户",
            "predicate": "居住地",
        }
        await mm._save_extracted_item(item)

    row = mm.store.db.get_memory("age28")
    assert row is not None
    assert row.get("valid_until") is not None
    visible = [m["id"] for m in mm.store.db.load_all(active_only=True)]
    assert "age28" not in visible
    assert any("上海" in m.get("content", "") for m in mm.store.db.load_all(active_only=True))

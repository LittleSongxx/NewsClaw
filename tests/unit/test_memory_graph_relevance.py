"""图谱边必须来自真实关系，不能因为“都是规则”就连成一圈。"""

from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient

from newsclaw.api.routes.memory import (
    _graph_tokens,
    _topic_overlap_weight,
)
from newsclaw.api.routes.memory import (
    router as memory_router,
)
from newsclaw.memory.manager import MemoryManager
from newsclaw.memory.types import MemoryPriority, MemoryType, SemanticMemory


def _manager(tmp_path) -> MemoryManager:
    return MemoryManager(
        data_dir=tmp_path / "memory",
        memory_md_path=tmp_path / "MEMORY.md",
        search_backend="fts5",
    )


def _save(
    manager: MemoryManager,
    content: str,
    *,
    mem_type: MemoryType = MemoryType.RULE,
    subject: str = "",
) -> str:
    mem = SemanticMemory(
        type=mem_type,
        priority=MemoryPriority.LONG_TERM,
        content=content,
        subject=subject,
        importance_score=0.8,
    )
    return manager.store.save_semantic(
        mem,
        scope="user",
        scope_owner="",
        user_id="desktop_user",
        workspace_id="default",
        skip_dedup=True,
    )


def _client(manager: MemoryManager) -> TestClient:
    app = FastAPI()
    app.include_router(memory_router)
    app.state.agent = SimpleNamespace(memory_manager=manager)
    return TestClient(app)


def test_topic_overlap_rejects_generic_newsroom_boilerplate():
    left = _graph_tokens("每天要出一期早报，用户是桌面端")
    right = _graph_tokens("8 到 12 条不要堆砌，当前搜索不稳定")
    assert _topic_overlap_weight(left, right) is None


def test_topic_overlap_keeps_same_claim_rewritten():
    left = _graph_tokens("AI 早报每条新闻需包含标题、来源和原始链接")
    right = _graph_tokens("AI 早报新闻条目必须写明标题、来源和原始链接")
    assert _topic_overlap_weight(left, right) is not None


def test_same_type_rules_stay_unlinked_when_topics_differ(tmp_path):
    manager = _manager(tmp_path)
    _save(manager, "每天要出一期早报")
    _save(manager, "用户是桌面端用户")
    _save(manager, "8 到 12 条不要堆砌")
    _save(manager, "关注大模型厂商动态")
    _save(manager, "采集失败后必须换源再试")
    graph = _client(manager).get("/api/memories/graph?limit=50").json()
    assert graph["meta"]["total_nodes"] == 5
    assert graph["meta"]["total_edges"] == 0
    assert graph["links"] == []


def test_same_subject_creates_real_edges(tmp_path):
    manager = _manager(tmp_path)
    _save(manager, "早报只收录最近 24 小时的新闻", subject="早报时间窗")
    _save(manager, "超过 48 小时的稿件不进当天早报", subject="早报时间窗")
    graph = _client(manager).get("/api/memories/graph?limit=50").json()
    assert graph["meta"]["total_edges"] == 1
    assert graph["links"][0]["edge_type"] == "same_subject"


def test_explicit_linked_ids_create_edges(tmp_path):
    manager = _manager(tmp_path)
    first = _save(manager, "web_search 当前不稳定，优先改用 web_fetch")
    second = _save(manager, "采集失败后换源，不要空等同一次搜索结果")
    manager.store.db.update_memory(second, {"metadata": {"linked_memory_ids": [first]}})
    graph = _client(manager).get("/api/memories/graph?limit=50").json()
    assert graph["meta"]["total_edges"] == 1
    assert graph["links"][0]["edge_type"] == "linked"


def test_graph_never_emits_same_type_edges(tmp_path):
    manager = _manager(tmp_path)
    _save(manager, "规则甲：固定产出三份稿件", mem_type=MemoryType.RULE)
    _save(manager, "规则乙：关注大模型厂商", mem_type=MemoryType.RULE)
    _save(manager, "规则丙：用户是桌面端", mem_type=MemoryType.RULE)
    graph = _client(manager).get("/api/memories/graph?limit=50").json()
    assert {e["edge_type"] for e in graph["links"]} == set()
    assert graph["meta"]["total_edges"] == 0

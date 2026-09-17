"""P2 契约：子 Agent 空上下文、单跳、create_agent 禁用。"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from newsclaw.agent.core import Agent
from newsclaw.agents.orchestrator import (
    MAX_DELEGATION_DEPTH,
    AgentOrchestrator,
    build_isolated_sub_session,
)
from newsclaw.agents.profile import AgentProfile, AgentType, ProfileStore
from newsclaw.sessions.session import Session, SessionConfig, SessionContext
from newsclaw.tools.handlers.agent import DYNAMIC_AGENT_POLICIES, AgentToolHandler

PARENT_SECRET = "PARENT_SECRET_XYZ"


def _make_session(session_id: str = "parent-sess") -> Session:
    ctx = SessionContext()
    ctx.agent_profile_id = "default"
    ctx.add_message("user", f"remember {PARENT_SECRET} forever")
    ctx.add_message("assistant", "ok, I will remember it")
    return Session(
        id=session_id,
        channel="cli",
        chat_id="chat-1",
        user_id="user-1",
        context=ctx,
        config=SessionConfig(),
    )


class _FakeToolCatalog:
    def __init__(self):
        self.deferred_tools: set[str] | None = None

    def set_deferred_tools(self, names):
        self.deferred_tools = set(names)


@pytest.fixture
def orchestrator(tmp_path):
    store = ProfileStore(tmp_path / "agents")
    store.save(AgentProfile(id="default", name="Default", type=AgentType.SYSTEM))
    store.save(
        AgentProfile(
            id="helper",
            name="Helper",
            type=AgentType.SYSTEM,
            fallback_profile_id="default",
        )
    )

    pool = MagicMock()
    mock_agent = MagicMock()
    mock_agent._is_sub_agent_call = False
    mock_agent._agent_profile = store.get("helper")
    mock_agent._last_finalized_trace = []
    mock_agent.agent_state = None
    mock_agent.chat_with_session = AsyncMock(return_value="isolated ok")
    pool.get_or_create = AsyncMock(return_value=mock_agent)
    pool.start = AsyncMock()
    pool.stop = AsyncMock()

    orch = AgentOrchestrator()
    orch._profile_store = store
    orch._pool = pool
    from newsclaw.agents.fallback import FallbackResolver

    orch._fallback = FallbackResolver(store)
    orch._log_dir = tmp_path / "delegation_logs"
    orch._log_dir.mkdir(parents=True, exist_ok=True)
    return orch, mock_agent, pool


def test_max_delegation_depth_is_one():
    assert MAX_DELEGATION_DEPTH == 1
    assert DYNAMIC_AGENT_POLICIES["max_delegation_depth"] == 1


def test_isolated_sub_session_does_not_share_parent_messages():
    parent = _make_session()
    isolated = build_isolated_sub_session(parent, run_id="abc123", agent_profile_id="helper")
    assert isolated.id == "parent-sess:sub:abc123"
    assert isolated.context.messages == []
    assert isolated.context.messages is not parent.context.messages
    assert PARENT_SECRET in parent.context.messages[0]["content"]
    assert isolated.chat_id == parent.chat_id
    assert isolated.get_metadata("_parent_session_id") == parent.id


@pytest.mark.asyncio
async def test_sub_agent_messages_omit_parent_secret(orchestrator):
    orch, mock_agent, _pool = orchestrator
    session = _make_session()

    await orch.delegate(session, "default", "helper", "[任务指令]\ncollect sources")

    mock_agent.chat_with_session.assert_awaited()
    kwargs = mock_agent.chat_with_session.await_args.kwargs
    blob = str(kwargs["session_messages"])
    assert PARENT_SECRET not in blob
    assert kwargs["session_messages"] == []
    assert ":sub:" in kwargs["session_id"]
    assert kwargs["session"] is not session
    assert kwargs["session"].context.messages == []
    assert any(PARENT_SECRET in (m.get("content") or "") for m in session.context.get_messages())


@pytest.mark.asyncio
async def test_sub_agent_effective_tools_exclude_delegate_tools():
    agent = Agent.__new__(Agent)
    agent._tools = [
        {"name": "read_file", "category": "File System"},
        {"name": "delegate_to_agent", "category": "Agent"},
        {"name": "delegate_parallel", "category": "Agent"},
        {"name": "spawn_agent", "category": "Agent"},
        {"name": "create_agent", "category": "Agent"},
    ]
    agent._current_intent = None
    agent._is_sub_agent_call = True
    agent._agent_tool_names = frozenset(
        {"delegate_to_agent", "delegate_parallel", "create_agent", "spawn_agent"}
    )
    agent._cron_disabled_tools = set()
    agent._current_session_type = "cli"
    agent._discovered_tools = set()
    agent.tool_catalog = _FakeToolCatalog()
    agent._get_raw_context_window = lambda: 0

    names = [tool["name"] for tool in agent._effective_tools]
    assert "read_file" in names
    assert "delegate_to_agent" not in names
    assert "delegate_parallel" not in names
    assert "spawn_agent" not in names
    assert "create_agent" not in names


@pytest.mark.asyncio
async def test_handler_blocks_sub_agent_delegation():
    agent = MagicMock()
    agent._is_sub_agent_call = True
    handler = AgentToolHandler(agent)
    result = await handler.handle("delegate_to_agent", {"agent_id": "helper", "message": "x"})
    assert "子 Agent" in result
    assert "不允许" in result


@pytest.mark.asyncio
async def test_create_agent_is_rejected():
    agent = MagicMock()
    agent._is_sub_agent_call = False
    agent._current_session = _make_session()
    handler = AgentToolHandler(agent)
    result = await handler.handle(
        "create_agent",
        {"name": "New Persona", "description": "invent a personality", "force": True},
    )
    assert "已禁用" in result
    assert "spawn_agent" in result


@pytest.mark.asyncio
async def test_fallback_stays_at_same_depth(orchestrator):
    orch, mock_agent, pool = orchestrator
    session = _make_session()
    depths: list[int] = []
    original_dispatch = orch._dispatch

    async def _spy_dispatch(*args, **kwargs):
        depths.append(int(kwargs.get("depth", args[3] if len(args) > 3 else 0)))
        return await original_dispatch(*args, **kwargs)

    orch._dispatch = _spy_dispatch  # type: ignore[method-assign]
    orch._fallback.should_use_fallback = MagicMock(return_value=True)
    orch._fallback.get_effective_profile = MagicMock(return_value="default")
    mock_agent.chat_with_session = AsyncMock(side_effect=RuntimeError("boom"))
    pool.get_or_create = AsyncMock(return_value=mock_agent)

    # 子 Agent 失败后换画像必须仍是 depth=1，不能变成孙 Agent。
    result = await orch._dispatch(session, "task", "helper", depth=1, from_agent="default")
    assert depths[0] == 1
    assert 1 in depths
    assert all(d <= MAX_DELEGATION_DEPTH for d in depths)
    assert depths.count(1) >= 2
    assert "处理失败" in result or "boom" in result
    orch._fallback.get_effective_profile.assert_called()

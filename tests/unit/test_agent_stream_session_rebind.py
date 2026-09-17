"""流式准备在子任务里写入会话后，父任务必须重新绑定。

``chat_with_session_stream`` 用 ``asyncio.create_task`` 跑
``_prepare_session_context``，好让准备阶段心跳还能往外推。
ContextVar / TLS 只向子任务传播，不会回写父任务；工具（含委派）
跑在父任务上。本测试先复现「子任务写了、父任务仍是 None」，
再断言准备成功后父任务能看到同一个 session 对象。
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

from newsclaw.agent.core import Agent
from newsclaw.core.agent_state import AgentState


def _make_chat_agent() -> Agent:
    agent = Agent.__new__(Agent)
    agent.agent_state = AgentState()
    agent._initialized = True
    agent._preferred_endpoint = None
    agent._pending_cancels = {}
    agent.brain = SimpleNamespace(_llm_client=None)
    agent._resolve_conversation_id = lambda _session, session_id: session_id
    agent._cleanup_session_state = lambda _im_tokens: None
    return agent


@pytest.mark.asyncio
async def test_child_prepare_session_is_invisible_until_parent_adopts() -> None:
    """仅子任务赋值时，父任务读到 None；adopt 之后必须是同一对象。"""
    agent = Agent.__new__(Agent)
    session = object()

    async def _child_prepare() -> None:
        agent._current_session = session
        agent._current_session_id = "child-sid"
        agent._current_conversation_id = "child-cid"
        assert agent._current_session is session

    await asyncio.create_task(_child_prepare())
    assert agent._current_session is None
    assert agent._current_session_id is None
    assert agent._current_conversation_id is None

    agent._adopt_session_on_current_task(
        session=session,
        session_id="parent-sid",
        conversation_id="parent-cid",
    )
    assert agent._current_session is session
    assert agent._current_session_id == "parent-sid"
    assert agent._current_conversation_id == "parent-cid"


@pytest.mark.asyncio
async def test_chat_with_session_stream_rebinds_session_after_child_prepare() -> None:
    """流式路径：子任务 prepare 结束后，父任务必须能读到传入的 session。"""
    agent = _make_chat_agent()
    session = object()
    seen: dict[str, object] = {}

    async def _prepare_session_context(**kwargs):
        # 与真实 prepare 一样：在子任务里写入 TLS。
        agent._current_session = kwargs["session"]
        assert agent._current_session is session
        return [], "cli", None, "conv-1", None

    checks = 0

    def _is_session_cancelled(_session_id):
        nonlocal checks
        checks += 1
        if checks >= 2:
            # 第二次检查发生在 prepare 成功之后、推理之前，此时已在父任务。
            seen["session"] = agent._current_session
            seen["session_id"] = agent._current_session_id
            seen["conversation_id"] = agent._current_conversation_id
            return True
        return False

    agent._prepare_session_context = _prepare_session_context
    agent._is_session_cancelled = _is_session_cancelled
    agent._consume_pending_cancel = lambda _session_id: None
    agent._build_slow_compiler_hint = lambda _session_id: None

    events = [
        event
        async for event in agent.chat_with_session_stream(
            message="委派给编辑去写早报",
            session_messages=[],
            session_id="session-1",
            session=session,
        )
    ]

    assert seen["session"] is session
    assert seen["session_id"] == "session-1"
    assert seen["conversation_id"] == "conv-1"
    assert events[-2:] == [
        {"type": "text_delta", "content": "✅ 好的，已停止当前任务。"},
        {"type": "done"},
    ]

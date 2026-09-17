"""出厂 protect / DONT_ASK 行业语义 / off=拒绝 / 意图门不再旁路。

钉死面试可讲的策略门卫契约：出厂先问、dont_ask 未预批则拒绝、
关掉安全 = 全部拒绝、TRUST 不再无条件放行破坏意图。
"""

from __future__ import annotations

from pathlib import Path

from newsclaw.core.policy_v2 import (
    ConfirmationMode,
    DecisionAction,
    PolicyConfigV2,
    PolicyContext,
    PolicyEngineV2,
    SessionRole,
    ToolCallEvent,
)
from newsclaw.core.policy_v2.defaults import FACTORY_DEFAULT_PROFILE, PROFILE_BUNDLES
from newsclaw.core.policy_v2.enums import ApprovalClass
from newsclaw.core.policy_v2.matrix import lookup
from newsclaw.core.policy_v2.models import MessageIntentEvent
from newsclaw.core.policy_v2.schema import SecurityProfileConfig


def _ctx(*, mode: ConfirmationMode = ConfirmationMode.DEFAULT) -> PolicyContext:
    return PolicyContext(
        session_id="industry-defaults",
        workspace=Path("/tmp"),
        session_role=SessionRole.AGENT,
        confirmation_mode=mode,
        is_owner=True,
    )


def test_factory_default_profile_is_protect() -> None:
    assert FACTORY_DEFAULT_PROFILE == "protect"
    assert PROFILE_BUNDLES["protect"]["confirmation"]["mode"] == "default"
    cfg = PolicyConfigV2()
    assert cfg.profile.current == "protect"
    assert cfg.confirmation.mode == ConfirmationMode.DEFAULT.value


def test_agent_dont_ask_exec_capable_denies() -> None:
    assert (
        lookup(SessionRole.AGENT, ConfirmationMode.DONT_ASK, ApprovalClass.EXEC_CAPABLE)
        == DecisionAction.DENY
    )
    engine = PolicyEngineV2(config=PolicyConfigV2())
    decision = engine.evaluate_tool_call(
        ToolCallEvent(tool="run_shell", params={"command": "echo hi"}),
        _ctx(mode=ConfirmationMode.DONT_ASK),
    )
    assert decision.action == DecisionAction.DENY
    assert decision.approval_class == ApprovalClass.EXEC_CAPABLE


def test_agent_dont_ask_mutating_global_denies() -> None:
    assert (
        lookup(SessionRole.AGENT, ConfirmationMode.DONT_ASK, ApprovalClass.MUTATING_GLOBAL)
        == DecisionAction.DENY
    )


def test_enabled_false_denies_run_shell_and_write_file() -> None:
    cfg = PolicyConfigV2(enabled=False, profile=SecurityProfileConfig(current="protect"))
    engine = PolicyEngineV2(config=cfg)
    for tool, params in (
        ("run_shell", {"command": "echo hi"}),
        ("write_file", {"path": "/tmp/x.txt", "content": "x"}),
    ):
        decision = engine.evaluate_tool_call(ToolCallEvent(tool=tool, params=params), _ctx())
        assert decision.action == DecisionAction.DENY, tool
        assert decision.reason == "security profile is off"


def test_intent_gate_not_unconditional_allow_under_default_and_trust() -> None:
    """明显破坏指令在 default/trust 下不得被意图门无条件放行。"""
    engine = PolicyEngineV2(config=PolicyConfigV2())
    event = MessageIntentEvent(
        message="rm -rf /",
        risk_intent={"operation_kind": "delete", "risk_level": "high"},
    )
    for mode in (ConfirmationMode.DEFAULT, ConfirmationMode.TRUST):
        decision = engine.evaluate_message_intent(event, _ctx(mode=mode))
        assert decision.action != DecisionAction.ALLOW, (
            f"{mode.value} must not unconditionally ALLOW destructive intent; "
            f"got {decision.action} chain={[s.name for s in decision.chain]}"
        )
        assert "intent_trust_bypass" not in {s.name for s in decision.chain}

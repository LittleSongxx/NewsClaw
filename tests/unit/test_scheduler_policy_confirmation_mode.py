"""回归：定时任务的策略上下文必须携带配置的确认模式。

背景（NewsClaw 主线踩坑）：scheduler.executor 构造 PolicyContext 时漏传
``confirmation_mode``，引擎退回内置 ``DEFAULT`` → 矩阵对写类工具
（MUTATING_SCOPED，如 write_file）一律 CONFIRM → 无人值守下 ``ask_owner``
无人在场即拒绝。"AI 早报"每日管线因此每期都写不出产物，且失败被静默合并，
排查成本极高。

本测试锁定两点契约：
1. 执行器解析出的确认模式与 POLICIES.yaml 一致（不是引擎内置 default）；
2. 在该模式 + 无人值守上下文下，工作区内的写入被矩阵直接 ALLOW，
   不会走到 unattended 的拒绝分支。
"""

from __future__ import annotations

from pathlib import Path

from newsclaw.core.policy_v2.context import ConfirmationMode, PolicyContext
from newsclaw.core.policy_v2.global_engine import get_config_v2, get_engine_v2
from newsclaw.core.policy_v2.models import ToolCallEvent
from newsclaw.scheduler.executor import _resolve_policy_confirmation_mode


def test_executor_inherits_configured_confirmation_mode() -> None:
    """执行器必须使用 POLICIES.yaml 的 confirmation.mode，而非内置 default。"""
    configured = get_config_v2().confirmation.mode
    assert _resolve_policy_confirmation_mode() == configured


def test_workspace_write_allowed_unattended_under_configured_mode(tmp_path: Path) -> None:
    """配置模式（trust）下，工作区内的 write_file 在无人值守时也直接放行。"""
    mode = _resolve_policy_confirmation_mode()
    root = str(tmp_path)
    ctx = PolicyContext(
        session_id="task:probe",
        working_directory=Path(root),
        workspace_roots=(Path(root),),
        channel="scheduler",
        is_owner=True,
        confirmation_mode=mode,
        is_unattended=True,
        unattended_strategy="ask_owner",
    )
    event = ToolCallEvent(
        tool="write_file",
        params={"path": f"{root}/data/newsroom/issues/2026-09-17/daily-brief.md", "content": "x"},
    )
    decision = get_engine_v2().evaluate_tool_call(event, ctx)
    assert decision.action.value == "allow", (
        f"配置模式 {mode} 下工作区写入应放行，实际 {decision.action} / {decision.reason}"
    )


def test_missing_mode_falls_back_to_default_confirm() -> None:
    """反证：不带确认模式时矩阵要求 CONFIRM —— 说明该字段是修复的关键。"""
    root = str(Path.cwd())
    ctx = PolicyContext(
        session_id="task:probe",
        working_directory=Path(root),
        workspace_roots=(Path(root),),
        channel="scheduler",
        is_unattended=True,
        unattended_strategy="ask_owner",
    )
    assert ctx.confirmation_mode == ConfirmationMode.DEFAULT
    event = ToolCallEvent(
        tool="write_file",
        params={"path": f"{root}/data/newsroom/issues/x.md", "content": "x"},
    )
    decision = get_engine_v2().evaluate_tool_call(event, ctx)
    assert decision.action.value == "confirm"

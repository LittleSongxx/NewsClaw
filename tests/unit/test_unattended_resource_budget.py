"""P4：无人值守预算墙、duration 硬停、费用记账、exit_reason。"""

from __future__ import annotations

import time
from types import SimpleNamespace

from newsclaw.agent.resource_budget import (
    UNATTENDED_DEFAULT_DURATION_SECONDS,
    UNATTENDED_DEFAULT_ITERATIONS,
    UNATTENDED_DEFAULT_SAME_TOOL_LIMIT,
    UNATTENDED_DEFAULT_TOOL_CALLS,
    BudgetAction,
    BudgetConfig,
    ResourceBudget,
    bind_active_task_budget,
    create_budget_from_settings,
    current_task_budget,
    reset_active_task_budget,
    resolve_task_budget,
    session_is_unattended,
)
from newsclaw.core._reasoning_runtime import _handle_budget_exhausted


def test_desktop_settings_zero_remain_unlimited(monkeypatch) -> None:
    from newsclaw.config import settings

    monkeypatch.setattr(settings, "task_budget_iterations", 0)
    monkeypatch.setattr(settings, "task_budget_duration", 0)
    monkeypatch.setattr(settings, "task_budget_tool_calls", 0)
    monkeypatch.setattr(settings, "same_tool_call_limit", 0)

    resolved = resolve_task_budget(unattended=False)
    assert resolved.max_iterations == 0
    assert resolved.max_duration_seconds == 0
    assert resolved.max_tool_calls == 0
    assert resolved.same_tool_call_limit == 0
    assert create_budget_from_settings(unattended=False).config.has_any_limit is False


def test_unattended_zero_settings_get_finite_defaults(monkeypatch) -> None:
    from newsclaw.config import settings

    monkeypatch.setattr(settings, "task_budget_iterations", 0)
    monkeypatch.setattr(settings, "task_budget_duration", 0)
    monkeypatch.setattr(settings, "task_budget_tool_calls", 0)
    monkeypatch.setattr(settings, "same_tool_call_limit", 0)

    resolved = resolve_task_budget(unattended=True)
    assert resolved.max_iterations == UNATTENDED_DEFAULT_ITERATIONS
    assert resolved.max_duration_seconds == UNATTENDED_DEFAULT_DURATION_SECONDS
    assert resolved.max_tool_calls == UNATTENDED_DEFAULT_TOOL_CALLS
    assert resolved.same_tool_call_limit == UNATTENDED_DEFAULT_SAME_TOOL_LIMIT

    budget = create_budget_from_settings(unattended=True)
    assert budget.config.max_iterations == UNATTENDED_DEFAULT_ITERATIONS
    assert budget.config.max_duration_seconds == UNATTENDED_DEFAULT_DURATION_SECONDS
    assert budget.config.max_tool_calls == UNATTENDED_DEFAULT_TOOL_CALLS


def test_explicit_unattended_settings_win(monkeypatch) -> None:
    from newsclaw.config import settings

    monkeypatch.setattr(settings, "task_budget_iterations", 12)
    monkeypatch.setattr(settings, "task_budget_duration", 90)
    monkeypatch.setattr(settings, "task_budget_tool_calls", 7)
    monkeypatch.setattr(settings, "same_tool_call_limit", 3)

    resolved = resolve_task_budget(unattended=True)
    assert resolved.max_iterations == 12
    assert resolved.max_duration_seconds == 90
    assert resolved.max_tool_calls == 7
    assert resolved.same_tool_call_limit == 3


def test_session_is_unattended_reads_flag() -> None:
    assert session_is_unattended(None) is False
    assert session_is_unattended(SimpleNamespace(is_unattended=False)) is False
    assert session_is_unattended(SimpleNamespace(is_unattended=True)) is True


def test_unattended_iteration_cap_pauses() -> None:
    """无人值守迭代撞顶 → PAUSE（引擎据此退出）。"""
    budget = create_budget_from_settings(unattended=True)
    budget.config.max_iterations = 3
    budget.start()
    for _ in range(3):
        budget.record_iteration()
    status = budget.check()
    assert status.action == BudgetAction.PAUSE
    assert status.dimension == "iterations"


def test_engine_budget_exhausted_reason_is_budget_exceeded() -> None:
    engine = SimpleNamespace(_last_exit_reason="normal")
    status = SimpleNamespace(dimension="iterations", usage_ratio=1.0, message="iterations done")
    msg = _handle_budget_exhausted(
        engine,
        status,
        conversation_id="sess-1",
        task_id="run-abc",
    )
    assert engine._last_exit_reason == "budget_exceeded"
    assert "budget_exceeded" in msg
    assert "run-abc" in msg
    assert "不得写成 ready" in msg


def test_duration_full_with_tool_progress_still_pauses() -> None:
    budget = ResourceBudget(
        BudgetConfig(max_duration_seconds=600, max_iterations=0, max_tool_calls=0)
    )
    budget.start()
    budget._start_time = time.time() - 700.0
    budget.record_tool_calls(1)
    status = budget.check()
    assert status.action == BudgetAction.PAUSE
    assert status.dimension == "duration"
    assert budget.duration_renewals == 0


def test_record_cost_accumulates_when_priced() -> None:
    budget = ResourceBudget()
    budget.start()
    budget.record_tokens(100, 50)
    budget.record_cost(0.012)
    assert budget.tokens_used == 150
    assert budget.cost_used == 0.012


def test_missing_price_does_not_record_zero_dollars() -> None:
    """未命中价表只记 token，不假装 0 元。"""
    budget = ResourceBudget()
    budget.start()
    budget.record_tokens(20, 10)
    cost = None
    if cost is not None:
        budget.record_cost(cost)
    assert budget.tokens_used == 30
    assert budget.cost_used == 0.0


def test_sub_agent_cost_rolls_into_parent() -> None:
    parent = ResourceBudget(BudgetConfig(max_cost_usd=1.0, max_iterations=80))
    parent.start()
    token = bind_active_task_budget(parent)
    try:
        assert current_task_budget() is parent
        child = parent.allocate_sub_budget(0.5)
        child.record_cost(0.2)
        child.record_tokens(10, 5)
        assert parent.cost_used == 0.2
        assert parent.tokens_used == 15
    finally:
        reset_active_task_budget(token)
    assert current_task_budget() is None

"""duration 到顶即停：有工具进展也不续命。

旧行为（已删除）：duration 100% 且近 60s 有进展 → WARNING 续期。
现行为：duration 命中上限一律 PAUSE。其它维度同样不豁免。
"""

from __future__ import annotations

import time

from newsclaw.agent.resource_budget import (
    BudgetAction,
    BudgetConfig,
    ResourceBudget,
)


def _make_budget(
    *,
    duration: int = 600,
    tokens: int = 0,
    iterations: int = 0,
    tool_calls: int = 0,
    cost: float = 0.0,
) -> ResourceBudget:
    config = BudgetConfig(
        max_tokens=tokens,
        max_cost_usd=cost,
        max_duration_seconds=duration,
        max_iterations=iterations,
        max_tool_calls=tool_calls,
    )
    budget = ResourceBudget(config)
    budget.start()
    return budget


def _shift_start(budget: ResourceBudget, seconds_ago: float) -> None:
    """将 budget 开始时间往前挪 seconds_ago 秒（伪造任务已运行了那么久）。"""
    budget._start_time = time.time() - seconds_ago


def test_had_recent_progress_false_initially() -> None:
    budget = _make_budget(duration=600)
    assert budget.had_recent_progress() is False


def test_had_recent_progress_true_after_tool_call() -> None:
    budget = _make_budget(duration=600)
    budget.record_tool_calls(1)
    assert budget.had_recent_progress() is True


def test_had_recent_progress_true_after_token_record() -> None:
    budget = _make_budget(duration=600)
    budget.record_tokens(input_tokens=100, output_tokens=50)
    assert budget.had_recent_progress() is True


def test_had_recent_progress_false_outside_window() -> None:
    budget = _make_budget(duration=600)
    budget.record_tool_calls(1)
    budget._last_tool_call_at = time.time() - 90.0
    assert budget.had_recent_progress() is False
    assert budget.had_recent_progress(window_seconds=120.0) is True


def test_record_iteration_does_not_count_as_progress() -> None:
    budget = _make_budget(duration=600)
    for _ in range(10):
        budget.record_iteration()
    assert budget.had_recent_progress() is False


def test_duration_100pct_with_recent_progress_still_pauses() -> None:
    """任务持续 700s 且刚调用过工具 → 仍然 PAUSE，不再续命。"""
    budget = _make_budget(duration=600)
    _shift_start(budget, seconds_ago=700.0)
    budget.record_tool_calls(1)

    status = budget.check()
    assert status.dimension == "duration"
    assert status.action == BudgetAction.PAUSE
    assert status.usage_ratio > 1.0
    assert budget.duration_renewals == 0


def test_duration_100pct_without_recent_progress_returns_pause() -> None:
    budget = _make_budget(duration=600)
    _shift_start(budget, seconds_ago=700.0)
    budget.record_tool_calls(1)
    budget._last_tool_call_at = time.time() - 100.0

    status = budget.check()
    assert status.dimension == "duration"
    assert status.action == BudgetAction.PAUSE
    assert budget.duration_renewals == 0


def test_tokens_100pct_with_recent_progress_still_pause() -> None:
    budget = _make_budget(tokens=1000, duration=0)
    budget.record_tokens(input_tokens=600, output_tokens=500)
    status = budget.check()
    assert status.dimension == "tokens"
    assert status.action == BudgetAction.PAUSE


def test_tool_calls_100pct_still_pause() -> None:
    budget = _make_budget(tool_calls=5, duration=0)
    budget.record_tool_calls(6)
    status = budget.check()
    assert status.dimension == "tool_calls"
    assert status.action == BudgetAction.PAUSE


def test_iterations_100pct_still_pause() -> None:
    budget = _make_budget(iterations=3, duration=0)
    for _ in range(4):
        budget.record_iteration()
    status = budget.check()
    assert status.dimension == "iterations"
    assert status.action == BudgetAction.PAUSE


def test_should_emit_threshold_only_once_per_dimension_and_level() -> None:
    budget = _make_budget(duration=600)
    assert budget.should_emit_threshold("duration", "warning") is True
    assert budget.should_emit_threshold("duration", "warning") is False
    assert budget.should_emit_threshold("duration", "downgrade") is True
    assert budget.should_emit_threshold("duration", "downgrade") is False
    assert budget.should_emit_threshold("tokens", "warning") is True


def test_start_resets_threshold_emissions() -> None:
    budget = _make_budget(duration=600)
    budget.should_emit_threshold("duration", "warning")
    budget.start()
    assert budget.should_emit_threshold("duration", "warning") is True


def test_duration_80pct_returns_warning() -> None:
    budget = _make_budget(duration=600)
    _shift_start(budget, seconds_ago=480.0)
    status = budget.check()
    assert status.dimension == "duration"
    assert status.action == BudgetAction.WARNING


def test_duration_90pct_returns_downgrade() -> None:
    budget = _make_budget(duration=600)
    _shift_start(budget, seconds_ago=540.0)
    status = budget.check()
    assert status.dimension == "duration"
    assert status.action == BudgetAction.DOWNGRADE


def test_long_running_task_pauses_once_duration_exhausted() -> None:
    """持续推进也不能越过 duration 墙。"""
    budget = _make_budget(duration=600)
    for elapsed in range(30, 750, 30):
        _shift_start(budget, seconds_ago=float(elapsed))
        budget.record_tool_calls(1)
        budget._last_tool_call_at = time.time()
        status = budget.check()
        if elapsed >= 600:
            assert status.action == BudgetAction.PAUSE
            assert status.dimension == "duration"
            return
        assert status.action != BudgetAction.PAUSE
    raise AssertionError("duration 墙未触发")

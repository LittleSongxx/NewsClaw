"""Task-level resource budget manager.

Like an OS managing process resources, this module allocates and
enforces budgets per task. When the budget nears exhaustion the
guard takes graduated action.

Dimensions:

* ``max_tokens`` — single-task token ceiling
* ``max_cost_usd`` — single-task cost ceiling
* ``max_duration_seconds`` — single-task wall-clock ceiling
* ``max_iterations`` — ReAct loop iteration ceiling
* ``max_tool_calls`` — total tool-call ceiling

Strategy:

* Warning (80 %): emit log + event, do not touch the prompt.
* Downgrade (90 %): flag a downgrade suggestion; caller decides
  whether to surface it.
* Pause (100 %): pause execution, notify the user, allow continue
  or budget bump.

Two internal absolute imports replace the previous relative ones so
the module can live under ``agent/``:

* ``from newsclaw.tracing.tracer import get_tracer`` (was
  ``from ..tracing.tracer``);
* ``from newsclaw.config import settings`` (was
  ``from ..config``).
"""

from __future__ import annotations

import logging
import time
from contextvars import ContextVar
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

logger = logging.getLogger(__name__)

# 无人值守（newsclaw run / scheduler）在 settings 仍为 0=不限时填入的有限默认。
# 桌面对话保持 Field 默认 0，禁止和无人值守共用「全 0」当唯一路径。
UNATTENDED_DEFAULT_ITERATIONS = 80
UNATTENDED_DEFAULT_DURATION_SECONDS = 1200
UNATTENDED_DEFAULT_TOOL_CALLS = 200
UNATTENDED_DEFAULT_SAME_TOOL_LIMIT = 8


class BudgetAction(Enum):
    """预算动作（值为严重程度，越大越严重）"""

    OK = 0
    WARNING = 1
    DOWNGRADE = 2
    PAUSE = 3


class BudgetExceeded(Exception):
    """预算耗尽异常"""

    def __init__(self, dimension: str, used: float, limit: float):
        self.dimension = dimension
        self.used = used
        self.limit = limit
        super().__init__(f"Budget exceeded: {dimension} ({used:.1f}/{limit:.1f})")


@dataclass
class BudgetConfig:
    """预算配置"""

    max_tokens: int = 0  # 0 = 不限制
    max_cost_usd: float = 0.0  # 0 = 不限制
    max_duration_seconds: int = 0  # 0 = 不限制
    max_iterations: int = 0  # 0 = 不限制
    max_tool_calls: int = 0  # 0 = 不限制

    warning_threshold: float = 0.80
    downgrade_threshold: float = 0.90
    pause_threshold: float = 1.0

    # 超出预算时的默认策略: "warning", "downgrade", "pause"
    exceed_policy: str = "pause"

    @property
    def has_any_limit(self) -> bool:
        return any(
            [
                self.max_tokens > 0,
                self.max_cost_usd > 0,
                self.max_duration_seconds > 0,
                self.max_iterations > 0,
                self.max_tool_calls > 0,
            ]
        )


@dataclass
class BudgetStatus:
    """预算状态快照"""

    action: BudgetAction
    dimension: str = ""
    usage_ratio: float = 0.0
    message: str = ""
    details: dict[str, Any] = field(default_factory=dict)


class ResourceBudget:
    """
    任务级资源预算管理器。

    每个任务开始时创建，随任务执行累加消耗。
    ReasoningEngine 每轮迭代调用 check() 检查预算。
    duration 命中上限即 PAUSE，不再因「近 60s 有进展」续命。
    """

    DEFAULT_PROGRESS_WINDOW_SECONDS: float = 60.0

    def __init__(
        self, config: BudgetConfig | None = None, parent: ResourceBudget | None = None
    ) -> None:
        self._config = config or BudgetConfig()
        self._parent: ResourceBudget | None = parent
        self._start_time: float = 0.0

        # 累计消耗
        self._tokens_used: int = 0
        self._cost_used: float = 0.0
        self._iterations_used: int = 0
        self._tool_calls_used: int = 0

        # 真实进展时间戳（诊断用）。duration 不再据此豁免 PAUSE。
        self._last_tool_call_at: float = 0.0
        self._last_token_record_at: float = 0.0

        # 预算警告已触发标记（避免重复告警）
        self._warning_fired: set[str] = set()
        self._downgrade_fired: bool = False

        # 阈值去抖：每个 (dimension, threshold_name) 仅触发一次 emit，
        # 避免 80% / 90% 反复刷屏污染日志和前端 banner。
        # threshold_name ∈ {"warning", "downgrade", "pause"}。
        self._emitted_thresholds: set[tuple[str, str]] = set()

        # 历史字段：duration 不再续期，恒为 0。
        self._duration_renewals: int = 0

    @property
    def config(self) -> BudgetConfig:
        return self._config

    @property
    def tokens_used(self) -> int:
        return self._tokens_used

    @property
    def cost_used(self) -> float:
        return self._cost_used

    @property
    def elapsed_seconds(self) -> float:
        if self._start_time <= 0:
            return 0.0
        return time.time() - self._start_time

    def start(self) -> None:
        """任务开始时调用"""
        self._start_time = time.time()
        self._tokens_used = 0
        self._cost_used = 0.0
        self._iterations_used = 0
        self._tool_calls_used = 0
        self._last_tool_call_at = 0.0
        self._last_token_record_at = 0.0
        self._warning_fired.clear()
        self._downgrade_fired = False
        self._emitted_thresholds.clear()
        self._duration_renewals = 0

    def record_tokens(self, input_tokens: int = 0, output_tokens: int = 0) -> None:
        """记录 token 消耗"""
        if input_tokens or output_tokens:
            self._tokens_used += input_tokens + output_tokens
            self._last_token_record_at = time.time()
        if self._parent is not None:
            self._parent.record_tokens(input_tokens, output_tokens)

    def record_cost(self, cost_usd: float) -> None:
        """记录成本"""
        self._cost_used += cost_usd
        if self._parent is not None:
            self._parent.record_cost(cost_usd)

    def record_iteration(self) -> None:
        """记录迭代"""
        self._iterations_used += 1
        if self._parent is not None:
            self._parent.record_iteration()

    def record_tool_calls(self, count: int = 1) -> None:
        """记录工具调用"""
        if count > 0:
            self._tool_calls_used += count
            self._last_tool_call_at = time.time()
        if self._parent is not None:
            self._parent.record_tool_calls(count)

    def had_recent_progress(self, window_seconds: float | None = None) -> bool:
        """近窗口内是否有真实进展（tool_call 或 token 产出）。

        ``record_iteration`` 不算进展——它每轮固定调用一次，会让本判定永远为真。
        仅 ``record_tool_calls`` / ``record_tokens`` 视为推进证据。
        """
        if window_seconds is None:
            window_seconds = self.DEFAULT_PROGRESS_WINDOW_SECONDS
        latest = max(self._last_tool_call_at, self._last_token_record_at)
        if latest <= 0:
            return False
        return (time.time() - latest) <= window_seconds

    def should_emit_threshold(self, dimension: str, threshold_name: str) -> bool:
        """阈值去抖：每个 (dimension, threshold_name) 仅返回 True 一次。

        触发后内部会自动登记，下次同维度同阈值返回 False。供 reasoning_engine
        判断是否需要向前端 yield budget_warning 事件。
        """
        key = (dimension, threshold_name)
        if key in self._emitted_thresholds:
            return False
        self._emitted_thresholds.add(key)
        return True

    @property
    def duration_renewals(self) -> int:
        return self._duration_renewals

    def allocate_sub_budget(self, ratio: float = 0.5) -> ResourceBudget:
        """为子任务/委派分配预算（按比例缩减）"""
        ratio = max(0.1, min(1.0, ratio))
        sub_config = BudgetConfig(
            max_tokens=int(self._config.max_tokens * ratio) if self._config.max_tokens else 0,
            max_cost_usd=self._config.max_cost_usd * ratio if self._config.max_cost_usd else 0.0,
            max_duration_seconds=int(self._config.max_duration_seconds * ratio)
            if self._config.max_duration_seconds
            else 0,
            max_iterations=int(self._config.max_iterations * ratio)
            if self._config.max_iterations
            else 0,
            max_tool_calls=int(self._config.max_tool_calls * ratio)
            if self._config.max_tool_calls
            else 0,
            warning_threshold=self._config.warning_threshold,
            downgrade_threshold=self._config.downgrade_threshold,
            pause_threshold=self._config.pause_threshold,
            exceed_policy=self._config.exceed_policy,
        )
        sub = ResourceBudget(sub_config, parent=self)
        sub.start()
        return sub

    def check(self) -> BudgetStatus:
        """
        检查预算状态，返回最严重的预算状态。

        应在每轮迭代开始时调用。
        """
        if not self._config.has_any_limit:
            return BudgetStatus(action=BudgetAction.OK)

        worst = BudgetStatus(action=BudgetAction.OK)

        checks = self._check_all_dimensions()
        for status in checks:
            if status.action.value > worst.action.value:
                worst = status

        if worst.action != BudgetAction.OK:
            logger.info(
                f"[Budget] {worst.action.name}: {worst.dimension} "
                f"({worst.usage_ratio:.0%}) — {worst.message}"
            )

            # Decision Trace
            try:
                from newsclaw.tracing.tracer import get_tracer

                tracer = get_tracer()
                tracer.record_decision(
                    decision_type="budget_check",
                    reasoning=worst.message,
                    outcome=worst.action.name,
                    dimension=worst.dimension,
                    usage_ratio=worst.usage_ratio,
                )
            except Exception:
                pass

        return worst

    def get_budget_prompt_warning(self) -> str:
        """Deprecated: no longer injects warnings into conversation."""
        return ""

    def get_summary(self) -> dict[str, Any]:
        """获取预算摘要"""
        return {
            "tokens_used": self._tokens_used,
            "cost_used": round(self._cost_used, 6),
            "elapsed_seconds": round(self.elapsed_seconds, 1),
            "iterations_used": self._iterations_used,
            "tool_calls_used": self._tool_calls_used,
            "limits": {
                "max_tokens": self._config.max_tokens,
                "max_cost_usd": self._config.max_cost_usd,
                "max_duration_seconds": self._config.max_duration_seconds,
                "max_iterations": self._config.max_iterations,
                "max_tool_calls": self._config.max_tool_calls,
            },
        }

    # ==================== 内部方法 ====================

    def _check_all_dimensions(self) -> list[BudgetStatus]:
        """检查所有预算维度"""
        results: list[BudgetStatus] = []

        if self._config.max_tokens > 0:
            results.append(
                self._check_dimension(
                    "tokens",
                    self._tokens_used,
                    self._config.max_tokens,
                )
            )

        if self._config.max_cost_usd > 0:
            results.append(
                self._check_dimension(
                    "cost_usd",
                    self._cost_used,
                    self._config.max_cost_usd,
                )
            )

        if self._config.max_duration_seconds > 0:
            results.append(
                self._check_dimension(
                    "duration",
                    self.elapsed_seconds,
                    self._config.max_duration_seconds,
                )
            )

        if self._config.max_iterations > 0:
            results.append(
                self._check_dimension(
                    "iterations",
                    self._iterations_used,
                    self._config.max_iterations,
                )
            )

        if self._config.max_tool_calls > 0:
            results.append(
                self._check_dimension(
                    "tool_calls",
                    self._tool_calls_used,
                    self._config.max_tool_calls,
                )
            )

        return results

    def _check_dimension(
        self,
        dimension: str,
        used: float,
        limit: float,
    ) -> BudgetStatus:
        """检查单个维度"""
        if limit <= 0:
            return BudgetStatus(action=BudgetAction.OK, dimension=dimension)

        ratio = used / limit

        if ratio >= self._config.pause_threshold:
            return BudgetStatus(
                action=BudgetAction.PAUSE,
                dimension=dimension,
                usage_ratio=ratio,
                message=f"{dimension} budget exhausted ({used:.1f}/{limit:.1f})",
            )

        if ratio >= self._config.downgrade_threshold:
            return BudgetStatus(
                action=BudgetAction.DOWNGRADE,
                dimension=dimension,
                usage_ratio=ratio,
                message=f"{dimension} approaching limit ({used:.1f}/{limit:.1f})",
            )

        if ratio >= self._config.warning_threshold:
            return BudgetStatus(
                action=BudgetAction.WARNING,
                dimension=dimension,
                usage_ratio=ratio,
                message=f"{dimension} at {ratio:.0%} of budget ({used:.1f}/{limit:.1f})",
            )

        return BudgetStatus(
            action=BudgetAction.OK,
            dimension=dimension,
            usage_ratio=ratio,
        )


# 当前 ReAct 任务的预算；子 Agent 通过它把消耗回写到父任务。
_active_task_budget: ContextVar[ResourceBudget | None] = ContextVar(
    "newsclaw_active_task_budget", default=None
)


@dataclass(frozen=True)
class ResolvedTaskBudget:
    """settings + 无人值守默认合并后的任务预算（桌面 0 保持不限）。"""

    max_tokens: int
    max_cost_usd: float
    max_duration_seconds: int
    max_iterations: int
    max_tool_calls: int
    same_tool_call_limit: int
    unattended: bool


def session_is_unattended(session: Any = None) -> bool:
    """只认 session 上的无人值守标记，不把交互式 CLI 当成 run。"""
    if session is None:
        return False
    if bool(getattr(session, "is_unattended", False)):
        return True
    get_metadata = getattr(session, "get_metadata", None)
    if callable(get_metadata):
        try:
            if get_metadata("is_unattended"):
                return True
        except Exception:
            pass
    metadata = getattr(session, "metadata", None)
    if isinstance(metadata, dict) and metadata.get("is_unattended"):
        return True
    return False


def resolve_task_budget(*, unattended: bool = False) -> ResolvedTaskBudget:
    """桌面保持 settings 的 0=不限；无人值守把 0 填成有限默认。

    token/cost 不发明数字：有用户配置才设上限，有价表则在运行时 record_cost。
    """
    try:
        from newsclaw.config import settings

        tokens = int(getattr(settings, "task_budget_tokens", 0) or 0)
        cost = float(getattr(settings, "task_budget_cost", 0.0) or 0.0)
        duration = int(getattr(settings, "task_budget_duration", 0) or 0)
        iterations = int(getattr(settings, "task_budget_iterations", 0) or 0)
        tool_calls = int(getattr(settings, "task_budget_tool_calls", 0) or 0)
        same_tool = int(getattr(settings, "same_tool_call_limit", 0) or 0)
    except Exception:
        tokens = 0
        cost = 0.0
        duration = 0
        iterations = 0
        tool_calls = 0
        same_tool = 0

    if unattended:
        if duration <= 0:
            duration = UNATTENDED_DEFAULT_DURATION_SECONDS
        if iterations <= 0:
            iterations = UNATTENDED_DEFAULT_ITERATIONS
        if tool_calls <= 0:
            tool_calls = UNATTENDED_DEFAULT_TOOL_CALLS
        if same_tool <= 0:
            same_tool = UNATTENDED_DEFAULT_SAME_TOOL_LIMIT

    return ResolvedTaskBudget(
        max_tokens=max(0, tokens),
        max_cost_usd=max(0.0, cost),
        max_duration_seconds=max(0, duration),
        max_iterations=max(0, iterations),
        max_tool_calls=max(0, tool_calls),
        same_tool_call_limit=max(0, same_tool),
        unattended=unattended,
    )


def bind_active_task_budget(budget: ResourceBudget):
    """父循环登记预算，供同任务内的子 Agent 回写消耗。"""
    return _active_task_budget.set(budget)


def reset_active_task_budget(token: Any) -> None:
    _active_task_budget.reset(token)


def current_task_budget() -> ResourceBudget | None:
    return _active_task_budget.get()


def create_budget_from_settings(
    *,
    unattended: bool = False,
    parent: ResourceBudget | None = None,
) -> ResourceBudget:
    """从 settings 创建预算管理器。

    ``unattended=True`` 时把步数/时长/工具次数的 0 换成有限默认。
    子 Agent 应优先 ``parent.allocate_sub_budget``，消耗计入父任务。
    """
    resolved = resolve_task_budget(unattended=unattended)
    if parent is not None:
        return parent.allocate_sub_budget(0.5)
    config = BudgetConfig(
        max_tokens=resolved.max_tokens,
        max_cost_usd=resolved.max_cost_usd,
        max_duration_seconds=resolved.max_duration_seconds,
        max_iterations=resolved.max_iterations,
        max_tool_calls=resolved.max_tool_calls,
    )
    return ResourceBudget(config)

"""
自检系统

功能:
- 运行测试用例
- 分析 ERROR 日志
- 区分核心组件和工具错误
- 自动修复工具问题
- 修复后自测验证
- 生成每日报告
"""

import json
import logging
import tempfile
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from importlib import resources
from pathlib import Path
from typing import Any

from ..agent.brain import Brain
from ..config import settings
from ..tools.file import FileTool
from ..tools.shell import ShellTool
from .log_analyzer import ErrorPattern, LogAnalyzer

logger = logging.getLogger(__name__)


class SelfCheckPromptUnavailableError(RuntimeError):
    """Raised when neither a workspace override nor the bundled prompt is available."""


@dataclass
class TestCase:
    """测试用例"""

    id: str
    category: str  # qa, tools, search
    description: str
    input: Any
    expected: Any
    validator: str | None = None  # 验证函数名


@dataclass
class TestResult:
    """测试结果"""

    test_id: str
    passed: bool
    actual: Any = None
    error: str | None = None
    duration_ms: float = 0


@dataclass
class CheckReport:
    """自检报告"""

    timestamp: datetime
    total_tests: int
    passed: int
    failed: int
    results: list[TestResult] = field(default_factory=list)
    fixed_count: int = 0
    status: str = "unknown"  # healthy, degraded, critical

    @property
    def pass_rate(self) -> float:
        if self.total_tests == 0:
            return 0
        return self.passed / self.total_tests * 100


@dataclass
class FixRecord:
    """修复记录"""

    error_pattern: str
    component: str
    fix_action: str
    fix_time: datetime
    verified: bool = False
    verification_result: str = ""
    success: bool = False


_NOISE_ERROR_IDS = frozenset(
    {
        "asyncio_task_destroyed_pending",
        "lark_ws_keepalive_timeout",
        "filesystem_read_missing_editorial_policy",
        "filesystem_read_missing_feedback_export",
        "filesystem_read_missing_daily_brief",
        "newsroom_issue_file_not_found",
        "newsroom_collect_task_idle_iterations",
    }
)
_OPTIONAL_MISSING_FILES = (
    "editorial-policy.md",
    "feedback-export.json",
    "daily-brief.md",
    "xiaohongshu.md",
    "wechat.md",
)
_ERROR_TITLES = {
    "policy_context_contextvar_cross_context": "策略上下文跨协程重置，早报定时任务被误判失败",
    "feishu_adapter_missing_lark_oapi": "飞书 SDK 未就绪",
    "lark_ws_keepalive_timeout": "飞书长连接抖动",
    "asyncio_task_destroyed_pending": "关闭连接时残留异步任务",
    "filesystem_read_missing_editorial_policy": "编辑方针文件尚未生成",
    "filesystem_read_missing_feedback_export": "反馈导出文件尚未生成",
    "filesystem_read_missing_daily_brief": "当日简报还未写出",
    "newsroom_issue_file_not_found": "当期稿件文件还不存在",
    "newsroom_collect_task_idle_iterations": "采集任务核验后空转偏久",
}


def _one_line(text: str, limit: int = 80) -> str:
    collapsed = " ".join((text or "").split())
    if len(collapsed) <= limit:
        return collapsed
    return collapsed[: limit - 1] + "…"


def classify_selfcheck_item(item: dict) -> str:
    """把一条自检发现分成 action / watch / noise，决定是否推给用户。"""
    pattern = str(item.get("pattern") or item.get("error_id") or "").lower()
    message = str(item.get("message") or "").lower()
    severity = str(item.get("severity") or "").lower()
    blob = f"{pattern} {message}"
    if pattern in _NOISE_ERROR_IDS:
        return "noise"
    if "task was destroyed but it is pending" in blob:
        return "noise"
    if "keepalive" in blob and ("1011" in blob or "timeout" in blob or "ping" in blob):
        return "noise"
    if any(name in blob for name in _OPTIONAL_MISSING_FILES) and (
        "not found" in blob or "不存在" in blob or "filenotfound" in blob or "missing" in blob
    ):
        return "noise"
    if "idle" in blob and ("iteration" in blob or "空转" in blob):
        return "noise"
    if severity == "low":
        return "watch"
    if pattern == "selfcheck_paused" or "background_token_budget" in pattern:
        return "watch"
    return "action"


def humanize_error_title(item: dict) -> str:
    pattern = str(item.get("pattern") or item.get("error_id") or "").strip()
    if pattern in _ERROR_TITLES:
        return _ERROR_TITLES[pattern]
    if pattern:
        return pattern.replace("_", " ")
    return _one_line(str(item.get("message") or "未命名问题"), 40)


def is_selfcheck_log_noise(pattern) -> bool:
    """日志模式是否属于已知噪声（缺可选文件、连接抖动、SDK 关闭残留）。"""
    samples = getattr(pattern, "samples", None) or []
    key = str(getattr(pattern, "pattern", "") or "").lower()
    for sample in samples:
        msg = str(getattr(sample, "message", "") or "").lower()
        if "system:daily_selfcheck" in msg and "timed out" in msg:
            return True
        if "task was destroyed but it is pending" in msg:
            return True
        if "keepalive" in msg and ("1011" in msg or "timeout" in msg or "ping" in msg):
            return True
        if any(name in msg for name in _OPTIONAL_MISSING_FILES) and (
            "not found" in msg or "filenotfound" in msg or "no such file" in msg or "不存在" in msg
        ):
            return True
    if "task was destroyed" in key or "keepalive" in key:
        return True
    if any(name in key for name in _OPTIONAL_MISSING_FILES):
        return True
    return False


@dataclass
class DailyReport:
    """每日系统报告"""

    date: str
    timestamp: datetime

    # 错误统计
    total_errors: int = 0
    core_errors: int = 0
    tool_errors: int = 0

    # 修复统计
    fix_attempted: int = 0
    fix_success: int = 0
    fix_failed: int = 0

    # 详细内容
    core_error_patterns: list[dict] = field(default_factory=list)
    tool_error_patterns: list[dict] = field(default_factory=list)
    fix_records: list[FixRecord] = field(default_factory=list)

    # 记忆整理结果（如果有）
    memory_consolidation: dict | None = None

    # 任务复盘统计
    retrospect_summary: dict | None = None  # 复盘汇总

    # 记忆系统优化建议
    memory_insights: dict | None = None  # 从记忆中提取的优化建议

    # 报告状态
    reported: bool = False
    partial: bool = False
    status_note: str = ""
    filtered_noise_count: int = 0

    def to_dict(self) -> dict:
        return {
            "date": self.date,
            "timestamp": self.timestamp.isoformat(),
            "total_errors": self.total_errors,
            "core_errors": self.core_errors,
            "tool_errors": self.tool_errors,
            "fix_attempted": self.fix_attempted,
            "fix_success": self.fix_success,
            "fix_failed": self.fix_failed,
            "filtered_noise_count": self.filtered_noise_count,
            "core_error_patterns": self.core_error_patterns,
            "tool_error_patterns": self.tool_error_patterns,
            "fix_records": [
                {
                    "error_pattern": r.error_pattern,
                    "component": r.component,
                    "fix_action": r.fix_action,
                    "fix_time": r.fix_time.isoformat(),
                    "verified": r.verified,
                    "verification_result": r.verification_result,
                    "success": r.success,
                }
                for r in self.fix_records
            ],
            "memory_consolidation": self.memory_consolidation,
            "retrospect_summary": self.retrospect_summary,
            "memory_insights": self.memory_insights,
            "reported": self.reported,
            "partial": self.partial,
            "status_note": self.status_note,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "DailyReport":
        timestamp = data.get("timestamp") or datetime.now().isoformat()
        if isinstance(timestamp, str):
            try:
                timestamp = datetime.fromisoformat(timestamp)
            except ValueError:
                timestamp = datetime.now()
        return cls(
            date=str(data.get("date") or datetime.now().strftime("%Y-%m-%d")),
            timestamp=timestamp,
            total_errors=int(data.get("total_errors") or 0),
            core_errors=int(data.get("core_errors") or 0),
            tool_errors=int(data.get("tool_errors") or 0),
            fix_attempted=int(data.get("fix_attempted") or 0),
            fix_success=int(data.get("fix_success") or 0),
            fix_failed=int(data.get("fix_failed") or 0),
            filtered_noise_count=int(data.get("filtered_noise_count") or 0),
            core_error_patterns=list(data.get("core_error_patterns") or []),
            tool_error_patterns=list(data.get("tool_error_patterns") or []),
            memory_consolidation=data.get("memory_consolidation"),
            retrospect_summary=data.get("retrospect_summary"),
            memory_insights=data.get("memory_insights"),
            reported=bool(data.get("reported")),
            partial=bool(data.get("partial")),
            status_note=str(data.get("status_note") or ""),
        )

    def _partition_findings(self) -> tuple[list[dict], list[dict], list[dict]]:
        actions: list[dict] = []
        watches: list[dict] = []
        noises: list[dict] = []
        for item in [*self.core_error_patterns, *self.tool_error_patterns]:
            bucket = classify_selfcheck_item(item)
            if bucket == "action":
                actions.append(item)
            elif bucket == "watch":
                watches.append(item)
            else:
                noises.append(item)
        return actions, watches, noises

    def _status_label(self, actions: list[dict]) -> str:
        if self.partial:
            return "部分完成"
        if actions:
            return f"需要关注（{len(actions)} 项）"
        if self.total_errors == 0 and not self.filtered_noise_count:
            return "正常"
        return "可继续运行"

    def to_digest(self) -> str:
        """给 IM 用的短报告：只说状态、要处理的事、已消化的噪声。"""
        from ..core.task_monitor import summarize_task_description

        actions, watches, noises = self._partition_findings()
        lines = [
            f"📋 每日自检 · {self.date}",
            f"状态：{self._status_label(actions)}",
            "",
        ]
        if self.partial:
            lines.append(f"说明：{self.status_note or '本轮未全部完成，下次继续。'}")
            lines.append("")

        if actions:
            lines.append("⚠️ 需要处理")
            for index, item in enumerate(actions, 1):
                lines.append(f"{index}. {humanize_error_title(item)}")
                note = str(item.get("note_to_user") or "").strip()
                detail = _one_line(note or str(item.get("message") or ""), 90)
                if detail:
                    lines.append(f"   → {detail}")
            lines.append("")

        if watches:
            lines.append("👀 留意")
            for item in watches[:4]:
                lines.append(f"· {humanize_error_title(item)}")
            lines.append("")

        digested = self.filtered_noise_count + len(noises)
        if digested:
            lines.append("ℹ️ 已消化（不必处理）")
            shown = 0
            for item in noises[:5]:
                lines.append(f"· {humanize_error_title(item)}")
                shown += 1
            leftover = digested - shown
            if leftover > 0:
                lines.append(f"· 另有 {leftover} 条同类噪声（缺文件/连接抖动等）")
            lines.append("")

        records = (self.retrospect_summary or {}).get("records") or []
        if records:
            lines.append("📊 任务复盘")
            for record in records[:4]:
                desc = summarize_task_description(str(record.get("description") or ""))
                duration = int(record.get("duration_seconds") or 0)
                minutes, seconds = divmod(duration, 60)
                dur = f"{minutes}分{seconds}秒" if minutes else f"{seconds}秒"
                analysis = _one_line(str(record.get("retrospect_result") or ""), 48)
                extra = f" · {analysis}" if analysis else ""
                lines.append(f"· {desc} · {dur}{extra}")
            lines.append("")

        suggestions = (self.memory_insights or {}).get("optimization_suggestions") or []
        if suggestions:
            lines.append("💡 优化建议")
            for suggestion in suggestions[:3]:
                lines.append(f"· {_one_line(str(suggestion), 80)}")
            lines.append("")

        if self.fix_attempted:
            lines.append(
                f"自动修复：尝试 {self.fix_attempted}，成功 {self.fix_success}，失败 {self.fix_failed}"
            )
            lines.append("")

        lines.append("完整技术报告已保存在本地。需要细节时再说「看完整自检报告」。")
        return "\n".join(lines).strip() + "\n"

    def to_markdown(self) -> str:
        """本地归档用的稍详报告；IM 推送走 to_digest，避免整份任务包刷屏。"""
        from ..core.task_monitor import summarize_task_description

        actions, watches, noises = self._partition_findings()
        lines = [
            f"# 每日系统报告 - {self.date}",
            "",
            f"状态：{self._status_label(actions)}",
            "",
            f"- 总错误数: {self.total_errors}",
            f"- 需处理: {len(actions)}",
            f"- 已过滤噪声: {self.filtered_noise_count + len(noises)}",
            f"- 尝试修复: {self.fix_attempted}（成功 {self.fix_success} / 失败 {self.fix_failed}）",
            "",
        ]
        if self.partial:
            lines.extend(
                [
                    f"> 本轮自检部分完成：{self.status_note or '后台预算已用尽'}",
                    "",
                ]
            )

        if actions:
            lines.append("## 需要处理")
            lines.append("")
            for item in actions:
                lines.append(f"### {humanize_error_title(item)}")
                if item.get("logger"):
                    lines.append(f"- 模块: `{item.get('logger')}`")
                note = str(item.get("note_to_user") or "").strip()
                message = _one_line(str(item.get("message") or ""), 160)
                if note:
                    lines.append(f"- 建议: {note}")
                elif message:
                    lines.append(f"- 说明: {message}")
                elif item.get("requires_restart"):
                    lines.append("- 建议: 修复代码后重启服务")
                lines.append("")

        if watches or noises:
            lines.append("## 已消化 / 可观察")
            lines.append("")
            for item in [*watches, *noises][:8]:
                lines.append(f"- {humanize_error_title(item)}")
            leftover = len(watches) + len(noises) - 8
            if leftover > 0:
                lines.append(f"- 另外 {leftover} 条同类项未展开")
            lines.append("")

        if self.fix_records:
            lines.append("## 工具修复记录")
            lines.append("")
            for record in self.fix_records:
                status = "已修复" if record.success else "修复失败"
                lines.append(f"- [{status}] {record.error_pattern}（{record.component}）")
            lines.append("")

        if self.retrospect_summary:
            rs = self.retrospect_summary
            lines.append("## 任务复盘")
            lines.append("")
            lines.append(
                f"- {rs.get('total_tasks', 0)} 个任务，总耗时 {rs.get('total_duration', 0):.0f} 秒"
            )
            for record in rs.get("records") or []:
                desc = summarize_task_description(str(record.get("description") or ""))
                duration = int(record.get("duration_seconds") or 0)
                analysis = _one_line(str(record.get("retrospect_result") or ""), 120)
                lines.append(f"- **{desc}**（{duration}秒）")
                if analysis:
                    lines.append(f"  - {analysis}")
            lines.append("")

        suggestions = (self.memory_insights or {}).get("optimization_suggestions") or []
        if suggestions:
            lines.append("## 优化建议")
            lines.append("")
            for suggestion in suggestions:
                lines.append(f"- {suggestion}")
            lines.append("")

        lines.append(f"*报告生成时间: {self.timestamp.strftime('%Y-%m-%d %H:%M:%S')}*")
        return "\n".join(lines)


class SelfChecker:
    """
    自检系统

    - 运行测试用例
    - 分析失败原因
    - 自动修复代码
    - 记录学习经验
    """

    def __init__(
        self,
        brain: Brain,
        test_dir: Path | None = None,
        memory_manager=None,
    ):
        self.brain = brain
        self.test_dir = test_dir or (
            settings.project_root / "src" / "newsclaw" / "testing" / "cases"
        )
        self._memory_manager = memory_manager
        self.shell = ShellTool()
        self.file_tool = FileTool()

        self._test_cases: list[TestCase] = []

    def load_test_cases(self) -> int:
        """加载测试用例"""
        self._test_cases = []

        # 从测试目录加载
        if self.test_dir.exists():
            for category_dir in self.test_dir.iterdir():
                if category_dir.is_dir():
                    category = category_dir.name
                    for test_file in category_dir.glob("*.py"):
                        cases = self._load_test_file(test_file, category)
                        self._test_cases.extend(cases)

        # 添加内置测试用例
        self._test_cases.extend(self._get_builtin_tests())

        logger.info(f"Loaded {len(self._test_cases)} test cases")
        return len(self._test_cases)

    def _load_test_file(self, path: Path, category: str) -> list[TestCase]:
        """从文件加载测试用例"""
        # TODO: 实现从 Python 文件加载测试用例
        return []

    def _get_builtin_tests(self) -> list[TestCase]:
        """获取内置测试用例"""
        tests = []

        # 基础功能测试
        tests.append(
            TestCase(
                id="core_brain_001",
                category="core",
                description="Brain 基本响应测试",
                input="你好",
                expected="包含响应文本",
            )
        )

        tests.append(
            TestCase(
                id="core_shell_001",
                category="tools",
                description="Shell 命令执行测试",
                input="echo hello",
                expected="hello",
            )
        )

        tests.append(
            TestCase(
                id="core_file_001",
                category="tools",
                description="文件读写测试",
                input={"action": "write_read", "content": "test"},
                expected="test",
            )
        )

        return tests

    async def run_check(
        self,
        categories: list[str] | None = None,
        quick: bool = False,
    ) -> CheckReport:
        """
        运行自检

        Args:
            categories: 要测试的类别
            quick: 是否快速检查（只运行核心测试）

        Returns:
            CheckReport
        """
        logger.info("Starting self-check...")

        if not self._test_cases:
            self.load_test_cases()

        # 筛选测试用例
        tests = self._test_cases
        if categories:
            tests = [t for t in tests if t.category in categories]
        if quick:
            tests = [t for t in tests if t.category == "core"][:10]

        results = []
        passed = 0
        failed = 0

        for test in tests:
            result = await self._run_test(test)
            results.append(result)

            if result.passed:
                passed += 1
            else:
                failed += 1
                logger.warning(f"Test failed: {test.id} - {result.error}")

        # 确定状态
        pass_rate = passed / len(results) * 100 if results else 0
        if pass_rate >= 95:
            status = "healthy"
        elif pass_rate >= 80:
            status = "degraded"
        else:
            status = "critical"

        report = CheckReport(
            timestamp=datetime.now(),
            total_tests=len(results),
            passed=passed,
            failed=failed,
            results=results,
            status=status,
        )

        logger.info(f"Self-check complete: {status} ({pass_rate:.1f}% passed)")

        return report

    async def _run_test(self, test: TestCase) -> TestResult:
        """运行单个测试"""
        import time

        start = time.time()

        try:
            if test.category == "core":
                actual = await self._run_core_test(test)
            elif test.category == "tools":
                actual = await self._run_tool_test(test)
            else:
                actual = await self._run_generic_test(test)

            # 验证结果
            passed = self._validate(actual, test.expected)

            duration = (time.time() - start) * 1000

            return TestResult(
                test_id=test.id,
                passed=passed,
                actual=actual,
                duration_ms=duration,
            )

        except Exception as e:
            duration = (time.time() - start) * 1000
            return TestResult(
                test_id=test.id,
                passed=False,
                error=str(e),
                duration_ms=duration,
            )

    async def _run_core_test(self, test: TestCase) -> Any:
        """运行核心测试"""
        if "brain" in test.id:
            response = await self.brain.think(test.input)
            return response.content
        return None

    async def _run_tool_test(self, test: TestCase) -> Any:
        """运行工具测试"""
        if "shell" in test.id:
            result = await self.shell.run(test.input)
            return result.stdout.strip()
        elif "file" in test.id:
            if isinstance(test.input, dict):
                if test.input.get("action") == "write_read":
                    test_file = str(Path(tempfile.gettempdir()) / "newsclaw_test.txt")
                    await self.file_tool.write(test_file, test.input["content"])
                    return await self.file_tool.read(test_file)
        return None

    async def _run_generic_test(self, test: TestCase) -> Any:
        """运行通用测试"""
        # TODO: 实现更多测试类型
        return None

    def _validate(self, actual: Any, expected: Any) -> bool:
        """验证结果"""
        if expected is None:
            return actual is not None

        if isinstance(expected, str):
            if expected.startswith("包含"):
                return expected[2:] in str(actual) or str(actual) != ""
            return str(actual) == expected

        return actual == expected

    async def run_daily_check(
        self,
        since: datetime | None = None,
        max_runtime_seconds: int | None = None,
    ) -> DailyReport:
        """
        执行系统自检（LLM 驱动）

        流程:
        1. 本地匹配提取 ERROR 日志（从 since 时间开始）
        2. 生成错误摘要
        3. LLM 分析错误并决定修复策略
        4. 根据 LLM 决策执行修复
        5. 修复后自测验证
        6. 生成报告

        Args:
            since: 只分析此时间之后的日志和复盘记录。None 表示分析全部（首次运行）。
            max_runtime_seconds: 本轮自检的软时间预算；达到后保存部分报告并返回。

        Returns:
            DailyReport
        """
        logger.info(
            "Starting self-check (LLM-driven)"
            + (f", since={since.isoformat()}" if since else ", first run")
            + "..."
        )

        today = datetime.now().strftime("%Y-%m-%d")
        report = DailyReport(
            date=today,
            timestamp=datetime.now(),
        )
        deadline = (
            time.monotonic() + max_runtime_seconds
            if max_runtime_seconds and max_runtime_seconds > 0
            else None
        )

        def time_budget_reached() -> bool:
            return deadline is not None and time.monotonic() >= deadline

        def mark_partial(reason: str) -> None:
            if report.partial:
                return
            report.partial = True
            report.status_note = reason
            report.tool_error_patterns.append(
                {
                    "pattern": "selfcheck_paused",
                    "count": 1,
                    "logger": "newsclaw.evolution.self_check",
                    "message": reason,
                    "last_seen": datetime.now().isoformat(),
                }
            )

        # === 阶段 1: 收集所有问题信息（日志 + 记忆 + 复盘） ===

        # 1.1 提取日志错误（支持增量：only since last check）
        log_analyzer = LogAnalyzer(settings.log_dir_path)
        errors = log_analyzer.extract_errors_only(since=since)
        error_summary = ""
        patterns = {}

        if errors:
            patterns = log_analyzer.classify_errors(errors)
            patterns, dropped_noise = self._filter_selfcheck_feedback_patterns(patterns)
            report.filtered_noise_count = dropped_noise
            report.total_errors = sum(p.count for p in patterns.values())
            error_summary = log_analyzer.generate_error_summary(patterns)
            logger.info(f"Extracted {report.total_errors} errors from logs")
        else:
            logger.info("No errors found in logs")

        # 1.2 加载任务复盘汇总（在 LLM 分析之前）
        retrospect_info = ""
        try:
            from ..core.task_monitor import get_retrospect_storage

            retrospect_storage = get_retrospect_storage()
            report.retrospect_summary = retrospect_storage.get_summary(today)

            if report.retrospect_summary.get("total_tasks", 0) > 0:
                logger.info(
                    f"Loaded retrospect summary: {report.retrospect_summary['total_tasks']} tasks"
                )
                # 构建复盘信息摘要
                retrospect_info = self._build_retrospect_summary_for_llm(report.retrospect_summary)
        except Exception as e:
            logger.warning(f"Failed to load retrospect summary: {e}")

        # 1.3 从记忆系统提取错误教训（在 LLM 分析之前）
        memory_info = ""
        try:
            report.memory_insights = await self._extract_memory_insights()
            if report.memory_insights:
                logger.info(
                    f"Extracted memory insights: {report.memory_insights.get('total_errors', 0)} errors"
                )
                # 构建记忆信息摘要
                memory_info = self._build_memory_summary_for_llm(report.memory_insights)
        except Exception as e:
            logger.warning(f"Failed to extract memory insights: {e}")

        # === 阶段 2: 综合分析（日志 + 记忆 + 复盘 一起提交给 LLM） ===

        # 构建完整的分析输入
        full_analysis_input = self._build_full_analysis_input(
            error_summary=error_summary,
            retrospect_info=retrospect_info,
            memory_info=memory_info,
        )

        if not full_analysis_input.strip():
            logger.info("No issues to analyze")
            self._save_daily_report(report)
            return report

        try:
            if time_budget_reached():
                mark_partial("自检收集阶段已达到后台时间预算，已保存当前报告。")
                self._save_daily_report(report)
                return report

            # LLM 综合分析（如果有 brain）
            if self.brain:
                analysis_results = await self._analyze_errors_with_llm(
                    full_analysis_input,
                    deadline=deadline,
                )
                logger.info(f"LLM analyzed {len(analysis_results)} issues")
                if time_budget_reached():
                    mark_partial("自检分析已达到后台时间预算，已保存部分结果，下次继续。")
            else:
                # 没有 brain，使用规则匹配（降级模式）
                logger.warning("No brain available, using rule-based analysis")
                analysis_results = self._analyze_errors_with_rules(patterns)

            # === 阶段 3: 按分析结果归类错误（只报告，不自动修复） ===
            # 自动修复已在收敛中移除：无人值守修改工具/技能层与本项目的
            # 策略收紧方向矛盾（CONFIRM 默认 deny）。报告推送需人确认后生效。
            for result in analysis_results:
                if time_budget_reached():
                    mark_partial("自检分析已达到后台时间预算，已保存部分结果，下次继续。")
                    break
                error_type = result.get("error_type", "unknown")
                can_fix = result.get("can_fix", False)

                if error_type == "core" or not can_fix:
                    report.core_errors += 1
                    report.core_error_patterns.append(
                        {
                            "pattern": result.get("error_id", ""),
                            "count": 1,
                            "logger": result.get("module", "unknown"),
                            "message": result.get("analysis", ""),
                            "last_seen": datetime.now().isoformat(),
                            "note_to_user": result.get("note_to_user", ""),
                            "requires_restart": result.get("requires_restart", False),
                            "severity": result.get("severity", ""),
                        }
                    )
                else:
                    report.tool_errors += 1

                    # 记录工具错误模式（无论是否修复都记录）
                    report.tool_error_patterns.append(
                        {
                            "pattern": result.get("error_id", ""),
                            "count": 1,
                            "logger": result.get("module", "unknown"),
                            "message": result.get("analysis", ""),
                            "last_seen": datetime.now().isoformat(),
                        }
                    )

            from ..core.token_tracking import token_budget_exceeded

            if token_budget_exceeded():
                report.partial = True
                report.status_note = "本轮后台 token 预算已用完，自检已安全暂停。"
                report.tool_error_patterns.append(
                    {
                        "pattern": "background_token_budget_reached",
                        "count": 1,
                        "logger": "newsclaw.scheduler",
                        "message": "本轮后台 token 预算已用完，自检已安全暂停，后续问题下次继续处理。",
                        "last_seen": datetime.now().isoformat(),
                    }
                )

            logger.info(
                f"Daily check complete: {report.total_errors} errors, "
                f"core={report.core_errors}, tool={report.tool_errors}, "
                f"fixed={report.fix_success}, failed={report.fix_failed}"
            )

        except SelfCheckPromptUnavailableError as e:
            logger.error("Daily check stopped before LLM analysis: %s", e)
            mark_partial("系统自检提示词不可用，已跳过本轮 LLM 分析和自动修复。")
        except Exception as e:
            logger.error(f"Daily check failed: {e}", exc_info=True)

        # 保存报告
        self._save_daily_report(report)

        return report

    def _build_retrospect_summary_for_llm(self, retrospect_summary: dict) -> str:
        """
        构建复盘信息摘要（给 LLM 分析）

        Args:
            retrospect_summary: 复盘汇总数据

        Returns:
            Markdown 格式摘要
        """
        if not retrospect_summary or retrospect_summary.get("total_tasks", 0) == 0:
            return ""

        lines = [
            "## 任务复盘信息",
            "",
            f"- 今日复盘任务数: {retrospect_summary.get('total_tasks', 0)}",
            f"- 总耗时: {retrospect_summary.get('total_duration', 0):.0f}秒",
            f"- 平均耗时: {retrospect_summary.get('avg_duration', 0):.1f}秒",
            f"- 模型切换次数: {retrospect_summary.get('model_switches', 0)}",
            "",
        ]

        # 常见问题
        common_issues = retrospect_summary.get("common_issues", [])
        if common_issues:
            lines.append("### 复盘发现的常见问题")
            for issue in common_issues:
                lines.append(f"- [{issue.get('count', 0)}次] {issue.get('issue', '')}")
            lines.append("")

        # 复盘详情
        records = retrospect_summary.get("records", [])
        if records:
            lines.append("### 复盘详情")
            for r in records:
                desc = r.get("description", "")
                result = r.get("retrospect_result", "")
                lines.append(f"- **{desc}**")
                if result:
                    lines.append(f"  - 分析: {result}")
            lines.append("")

        return "\n".join(lines)

    def _build_memory_summary_for_llm(self, memory_insights: dict) -> str:
        """
        构建记忆信息摘要（给 LLM 分析）

        Args:
            memory_insights: 记忆优化建议数据

        Returns:
            Markdown 格式摘要
        """
        if not memory_insights:
            return ""

        lines = ["## 记忆系统中的错误教训", ""]

        # 错误教训
        error_list = memory_insights.get("error_list", [])
        if error_list:
            lines.append("### 历史错误教训（最近记录）")
            for err in error_list:
                source = err.get("source", "unknown")
                content = err.get("content", "")
                lines.append(f"- [{source}] {content}")
            lines.append("")

        # 规则约束
        rule_list = memory_insights.get("rule_list", [])
        if rule_list:
            lines.append("### 系统规则约束")
            for rule in rule_list:
                content = rule.get("content", "")
                lines.append(f"- {content}")
            lines.append("")

        return "\n".join(lines)

    def _build_full_analysis_input(
        self,
        error_summary: str,
        retrospect_info: str,
        memory_info: str,
    ) -> str:
        """
        构建完整的分析输入（日志 + 复盘 + 记忆）

        Args:
            error_summary: 日志错误摘要
            retrospect_info: 复盘信息摘要
            memory_info: 记忆信息摘要

        Returns:
            完整的分析输入（Markdown 格式）
        """
        sections = []

        if error_summary:
            sections.append(error_summary)

        if retrospect_info:
            sections.append(retrospect_info)

        if memory_info:
            sections.append(memory_info)

        if not sections:
            return ""

        # 添加综合分析说明
        header = """# 系统自检综合分析

以下信息来源：
1. **日志错误** - 今日 ERROR/CRITICAL 级别日志
2. **任务复盘** - 长时间任务的执行分析
3. **错误教训** - 记忆系统中记录的历史问题

请综合分析这些信息，识别需要修复的问题。

---

"""
        return header + "\n\n".join(sections)

    async def _extract_memory_insights(self) -> dict:
        """
        从记忆系统提取优化相关的信息

        提取的记忆类型:
        - ERROR: 错误教训（来自复盘、任务失败等）
        - RULE: 规则约束（用户设定的规则）

        Returns:
            记忆优化建议字典
        """
        try:
            from ..memory import MemoryManager, MemoryType

            memory_manager = self._memory_manager
            if memory_manager is None:
                memory_manager = MemoryManager(
                    data_dir=settings.project_root / "data" / "memory",
                    memory_md_path=settings.memory_path,
                    search_backend=settings.search_backend,
                    embedding_api_provider=settings.embedding_api_provider,
                    embedding_api_key=settings.embedding_api_key,
                    embedding_api_model=settings.embedding_api_model,
                )

            # 提取 ERROR 类型记忆
            error_memories = memory_manager.search_memories(
                memory_type=MemoryType.ERROR,
                limit=50,
            )

            # 提取 RULE 类型记忆
            rule_memories = memory_manager.search_memories(
                memory_type=MemoryType.RULE,
                limit=20,
            )

            # 转换为字典格式
            error_list = [
                {
                    "id": m.id,
                    "content": m.content,
                    "source": m.source,
                    "importance": m.importance_score,
                    "created_at": m.created_at.isoformat(),
                    "tags": m.tags,
                }
                for m in error_memories
            ]

            rule_list = [
                {
                    "id": m.id,
                    "content": m.content,
                    "importance": m.importance_score,
                    "created_at": m.created_at.isoformat(),
                }
                for m in rule_memories
            ]

            # 如果有足够的错误记忆，让 LLM 提取优化建议
            optimization_suggestions = []
            if len(error_list) >= 3 and self.brain:
                optimization_suggestions = await self._generate_optimization_suggestions(
                    error_list, rule_list
                )

            return {
                "error_memories": error_list,
                "rule_memories": rule_list,
                "total_errors": len(error_list),
                "total_rules": len(rule_list),
                "optimization_suggestions": optimization_suggestions,
            }

        except Exception as e:
            logger.error(f"Failed to extract memory insights: {e}")
            return {}

    async def _generate_optimization_suggestions(
        self, error_memories: list[dict], rule_memories: list[dict]
    ) -> list[str]:
        """
        使用 LLM 从记忆中生成优化建议

        Args:
            error_memories: 错误记忆列表
            rule_memories: 规则记忆列表

        Returns:
            优化建议列表
        """
        # 构建错误摘要
        error_summary = "\n".join(
            [f"- [{m.get('source', 'unknown')}] {m.get('content', '')}" for m in error_memories]
        )

        rule_summary = "\n".join([f"- {m.get('content', '')}" for m in rule_memories])

        prompt = f"""请分析以下系统记录的错误教训和规则约束，提取出最重要的优化建议。

## 错误教训（最近记录）
{error_summary if error_summary else "暂无"}

## 规则约束
{rule_summary if rule_summary else "暂无"}

请从这些信息中提取 3-5 条最重要的优化建议，每条建议简洁明了（不超过 50 字）。
用 JSON 数组格式输出，如：["建议1", "建议2", "建议3"]
"""

        try:
            response = await self.brain.think(
                prompt,
                system="你是一个系统优化专家。请从错误记录中提取可行的优化建议。只输出 JSON 数组，不要其他内容。",
                enable_thinking=False,
            )

            # 解析 JSON
            import re

            json_match = re.search(r"\[.*\]", response.content, re.DOTALL)
            if json_match:
                suggestions = json.loads(json_match.group())
                if isinstance(suggestions, list):
                    return [str(s) for s in suggestions]

            return []

        except Exception as e:
            logger.warning(f"Failed to generate optimization suggestions: {e}")
            return []

    async def _analyze_errors_with_llm(
        self,
        error_summary: str,
        deadline: float | None = None,
    ) -> list[dict]:
        """
        使用 LLM 分析错误并决定修复策略（支持分批处理）

        Args:
            error_summary: 错误摘要（Markdown 格式）

        Returns:
            分析结果列表
        """
        system_prompt = self._load_selfcheck_system_prompt()

        # 追加环境标识，让 LLM 知道当前环境类型
        env_hint = (
            "production" if settings.selfcheck_autofix else "development（自动修复已关闭，仅分析）"
        )
        system_prompt += f"\n\n当前环境: {env_hint}"

        # 检查摘要大小，如果太大则分批处理
        MAX_CHARS_PER_BATCH = 8000  # 每批最大字符数（约 2000 tokens）

        if len(error_summary) <= MAX_CHARS_PER_BATCH:
            # 摘要较小，直接处理
            if deadline is not None and time.monotonic() >= deadline:
                logger.info("Skipping selfcheck LLM analysis: time budget reached")
                return []
            return await self._analyze_single_batch(error_summary, system_prompt)

        # 摘要太大，分批处理
        logger.info(f"Error summary too large ({len(error_summary)} chars), splitting into batches")

        # 按 "### [" 分割成独立的错误块
        import re

        error_blocks = re.split(r"(?=### \[)", error_summary)

        # 保留头部信息
        header = ""
        if error_blocks and not error_blocks[0].startswith("### ["):
            header = error_blocks[0]
            error_blocks = error_blocks[1:]

        # 分批
        batches = []
        current_batch = header

        for block in error_blocks:
            if len(current_batch) + len(block) > MAX_CHARS_PER_BATCH:
                if current_batch.strip():
                    batches.append(current_batch)
                current_batch = header + block
            else:
                current_batch += block

        if current_batch.strip():
            batches.append(current_batch)

        logger.info(f"Split into {len(batches)} batches for LLM analysis")

        # 分批调用 LLM
        all_results = []
        for i, batch in enumerate(batches):
            if deadline is not None and time.monotonic() >= deadline:
                logger.info(
                    "Stopping selfcheck analysis after %s/%s batches: time budget reached",
                    i,
                    len(batches),
                )
                break
            try:
                from ..core.token_tracking import token_budget_exceeded

                if token_budget_exceeded():
                    logger.info(
                        "Stopping selfcheck analysis after %s/%s batches: token budget reached",
                        i,
                        len(batches),
                    )
                    break
            except Exception:
                pass
            logger.info(f"Analyzing batch {i + 1}/{len(batches)} ({len(batch)} chars)")
            try:
                batch_results = await self._analyze_single_batch(batch, system_prompt)
                all_results.extend(batch_results)
            except Exception as e:
                logger.error(f"Batch {i + 1} analysis failed: {e}")
                continue

        return all_results

    @staticmethod
    def _load_selfcheck_system_prompt() -> str:
        """Load the workspace override, then the prompt bundled in the package.

        The prompt controls whether unattended repairs may be attempted. If both
        sources are unavailable, fail closed instead of silently substituting a
        second, potentially divergent policy.
        """
        override_path = settings.project_root / "prompts" / "selfcheck_system.md"
        if override_path.is_file():
            try:
                prompt = override_path.read_text(encoding="utf-8").strip()
                if prompt:
                    logger.debug("Using workspace selfcheck prompt: %s", override_path)
                    return prompt
                logger.warning("Ignoring empty workspace selfcheck prompt: %s", override_path)
            except (OSError, UnicodeError) as e:
                logger.warning("Failed to read workspace selfcheck prompt %s: %s", override_path, e)

        try:
            bundled_path = resources.files("newsclaw").joinpath("prompts", "selfcheck", "system.md")
            if bundled_path.is_file():
                prompt = bundled_path.read_text(encoding="utf-8").strip()
                if prompt:
                    logger.debug("Using bundled selfcheck prompt: %s", bundled_path)
                    return prompt
                logger.error("Bundled selfcheck prompt is empty: %s", bundled_path)
        except (ModuleNotFoundError, OSError, TypeError, UnicodeError) as e:
            logger.error("Failed to read bundled selfcheck prompt: %s", e)

        message = (
            "Self-check system prompt is unavailable; checked workspace path "
            f"{override_path} and bundled resource newsclaw/prompts/selfcheck/system.md"
        )
        logger.error(message)
        raise SelfCheckPromptUnavailableError(message)

    async def _analyze_single_batch(self, error_summary: str, system_prompt: str) -> list[dict]:
        """
        分析单个批次的错误

        Args:
            error_summary: 错误摘要
            system_prompt: 系统提示词

        Returns:
            分析结果列表
        """
        user_prompt = f"""请分析以下错误日志摘要，针对每个错误输出分析结果（JSON 数组格式）：

{error_summary}

请直接输出 JSON 数组，不要其他内容。"""

        try:
            response = await self.brain.think(
                user_prompt,
                system=system_prompt,
            )

            # 解析 JSON 结果
            return self._parse_llm_analysis(response.content)

        except Exception as e:
            logger.error(f"LLM analysis failed: {e}")
            return []

    def _parse_llm_analysis(self, content: str) -> list[dict]:
        """
        解析 LLM 返回的分析结果

        Args:
            content: LLM 返回的内容

        Returns:
            分析结果列表
        """
        try:
            # 尝试提取 JSON 数组
            import re

            # 查找 JSON 数组
            json_match = re.search(r"\[[\s\S]*\]", content)
            if json_match:
                json_str = json_match.group()
                return self._normalize_llm_analysis(json.loads(json_str))

            # 尝试直接解析
            return self._normalize_llm_analysis(json.loads(content))

        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse LLM response as JSON: {e}")
            logger.debug(f"LLM response: {content}")
            return []

    def _normalize_llm_analysis(self, parsed: Any) -> list[dict]:
        """Normalize model output into analysis objects.

        Some models return a list of short strings even when asked for objects.
        Treat those as low-risk human-readable findings instead of failing the
        whole selfcheck run.
        """
        if isinstance(parsed, dict):
            parsed_items = [parsed]
        elif isinstance(parsed, list):
            parsed_items = parsed
        else:
            logger.warning(
                "Selfcheck LLM returned unsupported JSON type: %s", type(parsed).__name__
            )
            return []

        results: list[dict] = []
        for idx, item in enumerate(parsed_items, start=1):
            if isinstance(item, dict):
                results.append(item)
                continue
            if isinstance(item, str) and item.strip():
                results.append(
                    {
                        "error_id": f"llm_unstructured_finding_{idx}",
                        "module": "newsclaw.evolution.self_check",
                        "error_type": "core",
                        "analysis": item.strip(),
                        "severity": "low",
                        "can_fix": False,
                        "fix_instruction": "",
                        "fix_reason": "模型返回了非结构化建议，作为报告项保留。",
                        "requires_restart": False,
                        "note_to_user": item.strip(),
                    }
                )
                continue
            logger.debug("Ignoring unsupported selfcheck analysis item: %r", item)

        return results

    def _filter_selfcheck_feedback_patterns(
        self,
        patterns: dict[str, ErrorPattern],
    ) -> tuple[dict[str, ErrorPattern], int]:
        """丢掉自检回声和已知噪声，避免把缺文件/连接抖动当成核心故障。"""
        filtered: dict[str, ErrorPattern] = {}
        dropped = 0
        for key, pattern in patterns.items():
            if is_selfcheck_log_noise(pattern):
                dropped += pattern.count
                continue
            filtered[key] = pattern

        if dropped:
            logger.info("Filtered %s selfcheck noise / feedback errors", dropped)
        return filtered, dropped

    def _analyze_errors_with_rules(self, patterns: dict) -> list[dict]:
        """
        使用规则分析错误（降级模式，当没有 LLM 时使用）

        Args:
            patterns: 错误模式字典

        Returns:
            分析结果列表
        """
        results = []

        for pattern_key, pattern in patterns.items():
            sample = pattern.samples[0] if pattern.samples else None
            module = sample.logger_name if sample else "unknown"
            message = sample.message if sample else ""

            # 判断是否是核心组件
            is_core = pattern.component_type == "core"

            # 判断修复策略和生成修复指令
            fix_instruction = None
            can_fix = False

            if not is_core:
                message_lower = message.lower()
                if "permission" in message_lower or "access denied" in message_lower:
                    # 避免涉及操作系统层面的权限调整（尤其是 Windows）
                    fix_instruction = None
                    can_fix = False
                elif "not found" in message_lower or "no such file" in message_lower:
                    # 缺失路径不一定属于自动修复白名单，降级分析不得猜测创建位置。
                    fix_instruction = None
                    can_fix = False
                elif "cache" in message_lower or "corrupt" in message_lower:
                    # 清理缓存是破坏性操作；没有 LLM 确认范围时仅报告。
                    fix_instruction = None
                    can_fix = False
                elif "timeout" in message_lower:
                    # 进程清理通常涉及系统层面操作，报告给用户即可
                    fix_instruction = None
                    can_fix = False
                elif "connection" in message_lower:
                    # 连接错误通常需要人工检查
                    fix_instruction = None
                    can_fix = False

            results.append(
                {
                    "error_id": pattern_key,
                    "module": module,
                    "error_type": "core" if is_core else "tool",
                    "analysis": message,
                    "severity": "high" if is_core else "medium",
                    "can_fix": can_fix,
                    "fix_instruction": fix_instruction,
                    "fix_reason": "规则匹配（降级模式）",
                    "requires_restart": is_core,
                    "note_to_user": "需要人工检查" if is_core else None,
                }
            )

        return results

    FIX_TIMEOUT_SECONDS = 60

    async def _verify_fix(self, component: str) -> tuple[bool, str]:
        """
        验证修复是否成功

        Args:
            component: 组件名称

        Returns:
            (是否通过, 验证结果描述)
        """
        try:
            if "tools.file" in component or "file" in component.lower():
                # 测试文件读写
                test_file = settings.project_root / "data" / "test_verify.tmp"
                await self.file_tool.write(str(test_file), "verify_test")
                content = await self.file_tool.read(str(test_file))
                test_file.unlink(missing_ok=True)

                if content == "verify_test":
                    return True, "文件读写测试通过"
                return False, f"文件读写测试失败: {content}"

            elif "tools.shell" in component or "shell" in component.lower():
                # 测试 Shell 命令
                result = await self.shell.run("echo verify_test")
                if result.returncode == 0 and "verify_test" in result.stdout:
                    return True, "Shell 命令测试通过"
                return False, f"Shell 命令测试失败: {result.stderr}"

            elif "tools.mcp" in component or "mcp" in component.lower():
                # MCP 测试需要特殊处理
                return True, "MCP 组件需要手动验证"

            elif "channel" in component.lower():
                # 通道测试需要特殊处理
                return True, "通道组件需要手动验证"

            else:
                # 通用验证：检查目录是否存在
                data_dir = settings.project_root / "data"
                if data_dir.exists():
                    return True, "数据目录检查通过"
                return False, "数据目录不存在"

        except Exception as e:
            return False, f"验证失败: {str(e)}"

    def _save_daily_report(self, report: DailyReport) -> None:
        """保存每日报告"""
        selfcheck_dir = settings.selfcheck_dir
        selfcheck_dir.mkdir(parents=True, exist_ok=True)

        # 保存 JSON 格式
        json_file = selfcheck_dir / f"{report.date}_report.json"
        try:
            with open(json_file, "w", encoding="utf-8") as f:
                json.dump(report.to_dict(), f, ensure_ascii=False, indent=2)
            logger.info(f"Saved daily report: {json_file}")
        except Exception as e:
            logger.error(f"Failed to save report JSON: {e}")

        # 保存 Markdown 格式
        md_file = selfcheck_dir / f"{report.date}_report.md"
        try:
            with open(md_file, "w", encoding="utf-8") as f:
                f.write(report.to_markdown())
            logger.info(f"Saved daily report: {md_file}")
        except Exception as e:
            logger.error(f"Failed to save report MD: {e}")

    def get_pending_report(self) -> str | None:
        """
        获取未提交的报告（供早上主动汇报）

        Returns:
            报告内容（Markdown），如果没有则返回 None
        """
        selfcheck_dir = settings.selfcheck_dir
        if not selfcheck_dir.exists():
            return None

        # 查找昨天的报告
        yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")
        json_file = selfcheck_dir / f"{yesterday}_report.json"

        if not json_file.exists():
            return None

        try:
            with open(json_file, encoding="utf-8") as f:
                data = json.load(f)

            # 检查是否已提交
            if data.get("reported"):
                return None

            return DailyReport.from_dict(data).to_digest()

        except Exception as e:
            logger.error(f"Failed to get pending report: {e}")
            return None

    def mark_report_as_reported(self, date: str | None = None) -> bool:
        """
        标记报告为已提交

        Args:
            date: 日期，默认昨天

        Returns:
            是否成功
        """
        if not date:
            date = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")

        json_file = settings.selfcheck_dir / f"{date}_report.json"

        if not json_file.exists():
            return False

        try:
            with open(json_file, encoding="utf-8") as f:
                data = json.load(f)

            data["reported"] = True

            with open(json_file, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)

            return True

        except Exception as e:
            logger.error(f"Failed to mark report as reported: {e}")
            return False

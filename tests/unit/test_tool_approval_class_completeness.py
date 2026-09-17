"""全量工具的风险等级完整性测试（防止"决策了却不执行"的隐形故障）。

背景：策略矩阵对 **UNKNOWN** 类一律 CONFIRM，而无人值守的定时任务对 CONFIRM
默认拒绝——工具调用会被静默拒掉，模型只看到"用户已拒绝安全确认: deny"，表现为
"Agent 决策了要调用、但工具从未执行"。`feishu_doc` 与 `wiki_upsert` 都踩过这个坑。

运行时的等级解析 = ① handler 的 ``TOOL_CLASSES`` 类声明（explicit）
                  + ② classifier 的前缀启发式（read_/list_/get_/browser_ 等）
                  + ③ skill/mcp/plugin 自声明。
本测试**静态扫描**全部 handler 模块读取 ①（无需构造 Agent，快且完整），
再断言每个内置工具至少命中 ① 或 ②——新增工具忘记声明等级时会立刻失败。
"""

from __future__ import annotations

import importlib
import pkgutil

from newsclaw.core.policy_v2.classifier import ApprovalClassifier
from newsclaw.core.policy_v2.enums import ApprovalClass
from newsclaw.core.policy_v2.enums import DecisionSource
from newsclaw.tools import handlers as handlers_pkg
from newsclaw.tools.definitions import BASE_TOOLS


def _declared_classes() -> dict[str, str]:
    """静态扫描 handler 模块，收集所有 ``TOOL_CLASSES`` 类声明。"""
    declared: dict[str, str] = {}
    for module_info in pkgutil.iter_modules(handlers_pkg.__path__):
        name = module_info.name
        if name.startswith("_"):
            continue
        try:
            module = importlib.import_module(f"{handlers_pkg.__name__}.{name}")
        except Exception:  # 可选依赖缺失（如 Windows 桌面模块）时跳过
            continue
        for obj in vars(module).values():
            classes = getattr(obj, "TOOL_CLASSES", None)
            if isinstance(classes, dict):
                for tool, klass in classes.items():
                    declared[str(tool)] = str(getattr(klass, "value", klass))
    return declared


def _builtin_tool_names() -> list[str]:
    names = [
        str(d["name"])
        for d in BASE_TOOLS
        if isinstance(d, dict) and d.get("name")
    ]
    return sorted(set(names))


def test_every_builtin_tool_is_classifiable():
    """每个内置工具都必须能解析出等级：handler 显式声明，或命中前缀启发式。"""
    declared = _declared_classes()
    classifier = ApprovalClassifier()

    unresolved: list[str] = []
    for name in _builtin_tool_names():
        if name in declared:
            continue
        klass, source = classifier.classify_with_source(name, {}, None)
        if klass is ApprovalClass.UNKNOWN and source is DecisionSource.FALLBACK_UNKNOWN:
            unresolved.append(name)

    assert not unresolved, (
        f"以下工具既无 handler 显式声明、也不匹配前缀启发式（会落到 UNKNOWN → "
        f"无人值守被静默拒绝）：{unresolved}。请在其 handler 里加 TOOL_CLASSES。"
    )


def test_self_developed_tools_declare_explicit_class():
    """我们自研的工具必须**显式**声明等级（不依赖前缀猜测），且注册表能读到。"""
    from newsclaw.tools.handlers import default_handler_registry
    from newsclaw.tools.handlers.feishu_doc import create_handler as feishu_handler
    from newsclaw.tools.handlers.wiki import create_handler as wiki_handler

    default_handler_registry.register("feishu_doc", feishu_handler(None))
    default_handler_registry.register("wiki", wiki_handler(None))

    for tool in ("feishu_doc", "wiki_upsert"):
        klass, source = default_handler_registry.get_tool_class(tool)
        assert klass is not None, f"{tool} 未声明 TOOL_CLASSES"
        assert source is DecisionSource.EXPLICIT_HANDLER_ATTR, (
            f"{tool} 的分类来源是 {source}，期望来自 handler 的显式声明"
        )


def test_newsroom_preset_tools_are_all_classifiable():
    """早报主线（无人值守）用到的工具必须全部可解析——这是最关键的运行集合。"""
    from newsclaw.agents.presets import SYSTEM_PRESETS

    declared = _declared_classes()
    classifier = ApprovalClassifier()
    preset = next(p for p in SYSTEM_PRESETS if p.id == "ai-news-editor")

    bad: list[str] = []
    for name in preset.tools:
        if name in declared:
            continue
        klass, _source = classifier.classify_with_source(name, {}, None)
        if klass is ApprovalClass.UNKNOWN:
            bad.append(name)
    assert not bad, f"主线工具集里存在无法解析等级的工具：{bad}"

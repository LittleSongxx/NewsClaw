"""回归：委派规则注入必须与实际工具能力一致。

背景：NewsClaw 收敛后按 profile 白名单裁剪工具，早报管线 Agent 一度没有
delegate_* 却仍被注入整章「协作优先原则 / 给子 Agent 写 prompt 的原则」，
既白烧 token 又诱导模型调用不存在的工具。修复为按工具集动态门控；本测试
锁定该契约（含 ToolCatalog 内部 dict 形态这一曾导致判断恒假的坑）。
"""

from __future__ import annotations

from newsclaw.prompt.builder import _has_delegation_tools


class _FakeCatalog:
    def __init__(self, tools):
        self._tools = tools


def test_dict_catalog_with_delegation_tools():
    """ToolCatalog._tools 实际是 {name: tool} 的 dict —— 必须能识别。"""
    cat = _FakeCatalog({"delegate_parallel": {"name": "delegate_parallel"}, "read_file": {}})
    assert _has_delegation_tools(cat) is True


def test_dict_catalog_without_delegation_tools():
    cat = _FakeCatalog({"read_file": {}, "write_file": {}, "web_search": {}})
    assert _has_delegation_tools(cat) is False


def test_list_catalog_shape_supported():
    cat = _FakeCatalog([{"name": "delegate_to_agent"}, {"name": "read_file"}])
    assert _has_delegation_tools(cat) is True


def test_none_or_unexpected_shape_is_false():
    assert _has_delegation_tools(None) is False
    assert _has_delegation_tools(_FakeCatalog("not-a-container")) is False


def test_newsroom_preset_keeps_delegation_tools():
    """主线预设自身必须持有委派工具（并行采集依赖它）。"""
    from newsclaw.agents.presets import SYSTEM_PRESETS

    preset = next(p for p in SYSTEM_PRESETS if p.id == "ai-news-editor")
    assert "delegate_parallel" in preset.tools
    # 同时不应把浏览器整族带回来（当初的裁剪目标）
    assert "browser" not in preset.tools

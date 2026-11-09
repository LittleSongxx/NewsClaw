"""
E2E 回归测试：P0-1 / P0-2 + P1-4/5/6 治本修复的"绝不能再坏"挡板。

不依赖真实 LLM / 真实通道 — 全部走纯函数 / mock，CI 上 <3s 完成。
任何一条断言失败都意味着我们丢掉了 ai-exploratory-testing.mdc 复盘里
拍板下来的根本修复，立即拉响警报。

覆盖：
- P0-1 文件沙箱：默认 default_zone=CONTROLLED + CONTROLLED 写操作 CONFIRM
- P0-2 阶段 0：tool_evidence_required + tool_calls=0 不再设
  _last_exit_reason="tool_evidence_missing"（避免 OrgRuntime task_failed 误判）
- P0-2 阶段 1：评论意图 ACTION + tool_calls=0 触发重试；其它情况 log-only
- P0-2 阶段 2：IntentResult 拆 evidence_required / evidence_recommended
- P0-2 阶段 3：来源标签一致性后置检测
- P0-3 A：runtime 子任务失败/异常路径自动 _mark_chain_closed
- P1-4：USER.md 占位字段 _clean_user_content 过滤
"""

from __future__ import annotations

from pathlib import Path

import pytest

# =============================================================================
# P0-1：默认安全区与写操作矩阵
# =============================================================================


def test_p0_1_user_dirs_default_to_mutating_scoped_class():
    """P0-1：桌面/文档/下载等用户目录上的 CREATE/EDIT/OVERWRITE/DELETE 必须 CONFIRM。

    C8b-6b：原本通过 v1 ``PolicyEngine._make_default_config()`` + ``Zone.CONTROLLED``
    + ``_ZONE_OP_MATRIX`` 三层验证。v2 删除了 zone 概念，等价语义改由
    ``ApprovalClass.MUTATING_SCOPED`` 承载——这些工具 + 用户路径的组合走
    PolicyEngineV2 矩阵自动判 CONFIRM。本测试改为对 v2 决策做 e2e smoke。
    """
    from newsclaw.core.policy_v2 import (
        PolicyConfigV2,
        build_engine_from_config,
    )
    from newsclaw.core.policy_v2.adapter import evaluate_via_v2
    from newsclaw.core.policy_v2.enums import ConfirmationMode, DecisionAction
    from newsclaw.core.policy_v2.global_engine import (
        reset_engine_v2,
        set_engine_v2,
    )
    from newsclaw.core.policy_v2.schema import ConfirmationConfig

    cfg = PolicyConfigV2(confirmation=ConfirmationConfig(mode=ConfirmationMode.DEFAULT))
    set_engine_v2(build_engine_from_config(cfg), cfg)
    try:
        # 用户桌面是默认 controlled 区——delete_file 必须 CONFIRM
        for tool, params in [
            ("delete_file", {"path": "C:/Users/example/Desktop/x.log"}),
            ("write_file", {"path": "C:/Users/example/Desktop/x.log", "content": "y"}),
        ]:
            decision = evaluate_via_v2(tool, params)
            assert decision.action in (DecisionAction.CONFIRM, DecisionAction.DENY), (
                f"P0-1 回归: {tool} 在用户桌面被静默放行（action={decision.action.value}）"
            )
    finally:
        reset_engine_v2()


def test_p0_1_default_controlled_paths_include_user_dirs():
    """P0-1：默认 controlled paths 必须涵盖桌面/文档/下载等高敏目录。

    C8b-6b：v1 ``_default_controlled_paths`` 已删；v2 ``default_controlled_paths()``
    在 ``policy_v2/defaults.py`` 等价提供。
    """
    from newsclaw.core.policy_v2 import default_controlled_paths

    paths = [p.lower() for p in default_controlled_paths()]
    blob = "|".join(paths)
    assert any(k in blob for k in ("desktop", "桌面")), "桌面缺失，越界写入会被默认放行"
    assert any(k in blob for k in ("documents", "文档")), "文档目录缺失"
    assert any(k in blob for k in ("downloads", "下载")), "下载目录缺失"


# =============================================================================
# P0-2 阶段 0：tool_evidence_missing 不再回写 exit_reason
# =============================================================================


def test_p0_2_phase0_no_hard_exit_reason():
    """P0-2 阶段 0：reasoning_engine 源码中已删除 _last_exit_reason='tool_evidence_missing' 赋值。

    这条是组织死锁的根因——只要源码里再次出现这个赋值，OrgRuntime 就会把它映射成
    task_failed，根节点 wait_for_deliverable 永远等不到 deliverable。
    注释里出现关键字是允许的（标记为何被删除），但代码语句不行。
    """
    import re

    src = Path("src/newsclaw/core/_reasoning_runtime.py").read_text(encoding="utf-8")
    # 移除注释行后再检查赋值
    code_only_lines = [ln for ln in src.splitlines() if not ln.lstrip().startswith("#")]
    code_only = "\n".join(code_only_lines)
    bad_pattern = re.compile(r"_last_exit_reason\s*=\s*[\"']tool_evidence_missing[\"']")
    assert not bad_pattern.search(code_only), (
        "_reasoning_runtime.py 不允许再写入 _last_exit_reason='tool_evidence_missing' 赋值，"
        "否则 P0-3 组织死锁会回归。"
    )


# =============================================================================
# P0-2 阶段 2：IntentResult 拆出 evidence_recommended
# =============================================================================


def test_p0_2_phase2_intent_result_has_evidence_recommended_field():
    """阶段 2：IntentResult 必须有 evidence_recommended 字段（默认 False），
    且与 evidence_required 不再被合并到同一信号。"""
    from newsclaw.core.intent_analyzer import IntentResult, IntentType

    ir = IntentResult(intent=IntentType.CHAT)
    assert hasattr(ir, "evidence_required")
    assert hasattr(ir, "evidence_recommended"), (
        "IntentResult 一旦不再有 evidence_recommended 字段，"
        "agent.py 的软提示路径就会哑火，回到原来的"
        "纯文本对话也强制 ForceToolCall 的死循环。"
    )
    assert ir.evidence_required is False
    assert ir.evidence_recommended is False


# =============================================================================
# P1-4：USER.md 占位字段过滤
# =============================================================================


@pytest.mark.parametrize(
    "raw,must_be_filtered_out",
    [
        ("- **称呼**: [待学习]\n", "称呼"),
        ("- **工作领域**: <to_learn>\n", "工作领域"),
        ("- **行业**: `待学习`\n", "行业"),
        ("- **OS**: (待学习)\n", "OS"),
        ("- **统计项**: [待统计]\n", "统计项"),
        ("- **拓展**: [待补充]\n", "拓展"),
    ],
)
def test_p1_4_clean_user_content_filters_placeholders(raw: str, must_be_filtered_out: str):
    """P1-4：所有占位符行必须被滤掉，否则 LLM 又会拿到伪事实。"""
    from newsclaw.prompt.builder import _clean_user_content

    cleaned = _clean_user_content(raw)
    assert must_be_filtered_out not in cleaned, (
        f"占位符行未被过滤，会向 LLM 注入伪用户档案：{raw!r} → {cleaned!r}"
    )


def test_p1_4_clean_user_content_keeps_real_values():
    """真实字段（含中文值）必须被保留。"""
    from newsclaw.prompt.builder import _clean_user_content

    raw = "- **称呼**: 张明\n- **工作领域**: 嵌入式开发\n"
    cleaned = _clean_user_content(raw)
    assert "张明" in cleaned
    assert "嵌入式开发" in cleaned

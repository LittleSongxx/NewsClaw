"""NewsClaw 主线回归测试：data/newsroom/ 不应被判为受保护文件。

背景：RiskIntentClassifier 的通用规则把任何含 ``data/`` 的路径视为
``TargetKind.PROTECTED_FILE``，导致「AI 早报」每日管线（无人值守定时任务）
写入产物时每期都卡在确认门。修复为 data/newsroom/ 显式豁免，同时保证
``data/`` 其他部分（会话库、策略、凭证）仍然受保护。
"""

from __future__ import annotations

import pytest

from newsclaw.core.risk_intent import (
    AccessMode,
    TargetKind,
    classify_risk_intent,
)


@pytest.mark.parametrize(
    "message",
    [
        "把早报写入 /home/user/app/data/newsroom/issues/2026-09-17/daily-brief.md",
        "write the briefing to data/newsroom/issues/2026-09-17/xiaohongshu.md",
        "更新 data/newsroom/sources.yaml 里的信源权重",
        r"写入 C:\app\data\newsroom\issues\2026-09-17\wechat.md",
    ],
)
def test_newsroom_paths_are_not_protected(message: str) -> None:
    result = classify_risk_intent(message)
    # 契约：落在普通文件系统语义（而非 PROTECTED_FILE 的确认门）
    assert result.target_kind is TargetKind.FILE_SYSTEM, (
        f"data/newsroom/ 应判为 FILE_SYSTEM，但 target_kind={result.target_kind}, "
        f"reason={result.reason}, message={message!r}"
    )


@pytest.mark.parametrize(
    "message",
    [
        # 会话库/凭证类：PROTECTED_FILE
        "删除 data/agent.db 里的会话记录",
        "覆盖 ~/.ssh/config 里的配置",
        # 策略文件命中 SECURITY_POLICY（同属敏感目标），不是普通文件
        "修改 identity/POLICIES.yaml 的安全策略",
    ],
)
def test_other_sensitive_paths_still_protected(message: str) -> None:
    """豁免只针对 data/newsroom/，其余敏感路径的判定保持不变。"""
    result = classify_risk_intent(message)
    assert result.target_kind in (TargetKind.PROTECTED_FILE, TargetKind.SECURITY_POLICY), (
        f"敏感路径仍应判为受保护/策略目标，但 target_kind={result.target_kind}, "
        f"message={message!r}"
    )
    assert result.access_mode is AccessMode.WRITE

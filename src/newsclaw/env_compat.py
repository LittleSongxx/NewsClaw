"""NewsClaw 环境变量前缀。

历史上上游使用另一套产品前缀。再品牌化后只认 ``NEWSCLAW_*``，
不再把旧前缀映射过来，避免桌面端、安装脚本与 CLI 读到两套名字。
"""

from __future__ import annotations

from collections.abc import MutableMapping

ENV_PREFIX = "NEWSCLAW_"


def alias_legacy_env(environ: MutableMapping[str, str] | None = None) -> int:
    """已废弃：不再映射旧产品前缀。保留空实现，避免旧调用点在 import 时崩溃。"""
    return 0

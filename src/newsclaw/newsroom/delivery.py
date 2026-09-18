"""早报产物投递门：未 ready 的期次文件不准走 deliver_artifacts。

不检查非 newsroom 路径，避免影响普通聊天发文件。
"""

from __future__ import annotations

import json
import logging
import re
from datetime import datetime
from pathlib import Path

from newsclaw.newsroom.contract import load_manifest, newsroom_root, write_manifest

logger = logging.getLogger(__name__)

_ISSUE_DATE = re.compile(r"(?:^|/)issues/(\d{4}-\d{2}-\d{2})(?:/|$)")


def issue_dates_in_paths(paths: list[str]) -> list[str]:
    """从投递路径里抽出期次日期，只认 newsroom/issues/ 下的文件。"""
    root = newsroom_root().resolve()
    found: list[str] = []
    seen: set[str] = set()
    for raw in paths:
        text = str(raw or "").strip()
        if not text:
            continue
        try:
            resolved = Path(text).expanduser().resolve()
        except OSError:
            resolved = Path(text)
        match = _ISSUE_DATE.search(resolved.as_posix())
        if match is None:
            match = _ISSUE_DATE.search(text.replace("\\", "/"))
        if match is None:
            continue
        try:
            resolved.relative_to(root)
        except ValueError:
            if "newsroom/issues/" not in resolved.as_posix() and "newsroom/issues/" not in text:
                continue
        day = match.group(1)
        if day not in seen:
            seen.add(day)
            found.append(day)
    return found


def newsroom_delivery_block_reason(paths: list[str]) -> str | None:
    """若路径指向未 ready 的早报产物，返回拒绝文案。"""
    dates = issue_dates_in_paths(paths)
    if not dates:
        return None
    blocked: list[str] = []
    for day in dates:
        manifest, error = load_manifest(day, require_item_ledger=False)
        if manifest is None:
            blocked.append(f"{day} 没有合法 manifest")
            continue
        if error:
            blocked.append(f"{day} 契约未过：{error}")
            continue
        if manifest.status != "ready":
            blocked.append(f"{day} 当前 status={manifest.status}，不是 ready")
    if not blocked:
        return None
    return (
        "❌ 早报产物仅在 status=ready 后才能投递（"
        + "；".join(blocked)
        + "）。先写过检的 manifest，再 deliver_artifacts。"
    )


def mark_newsroom_delivered(paths: list[str], receipt_text: str) -> None:
    """投递成功后给当期 manifest 落 ``delivered_at``（best-effort）。

    只认 JSON 回执里的 ``ok: true``；解析失败或投递失败一律不标记——
    宁可漏标（退化为无记录），不可把没送达的期次标成已出门。
    """
    try:
        payload = json.loads(receipt_text)
        delivered = isinstance(payload, dict) and payload.get("ok") is True
    except (json.JSONDecodeError, TypeError):
        return
    if not delivered:
        return
    for day in issue_dates_in_paths(paths):
        manifest, error = load_manifest(day, require_item_ledger=False)
        if manifest is None or error or manifest.delivered_at:
            continue
        manifest.delivered_at = datetime.now().isoformat(timespec="seconds")
        try:
            write_manifest(manifest)
        except (ValueError, OSError) as exc:
            logger.warning("[Newsroom] delivered_at write failed for %s: %s", day, exc)

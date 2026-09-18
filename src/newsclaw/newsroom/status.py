"""主线健康总览：一个只读聚合回答「早报主线现在健康吗」。

各分区独立降级（某一区读失败只影响该区，端点整体仍返回），数据全部
来自磁盘现态（config / 任务存储 / 期次目录 / 反馈库），不做任何写操作。
"""

from __future__ import annotations

import logging
from datetime import date
from typing import Any

logger = logging.getLogger(__name__)


async def build_status(scheduler: Any = None) -> dict[str, Any]:
    """聚合主线健康状态；``scheduler`` 为 None 时任务分区报 present=False。"""
    from newsclaw.newsroom import contract, feedback
    from newsclaw.newsroom.config import load_config
    from newsclaw.newsroom.items import collect_seen_urls
    from newsclaw.newsroom.prompts import PROMPT_VERSION
    from newsclaw.newsroom.proposal import load_latest_proposal
    from newsclaw.newsroom.seed import DAILY_TASK_ID, REVIEW_TASK_ID

    cfg = load_config()
    payload: dict[str, Any] = {
        "config": {"enabled": cfg.enabled, "error": cfg.load_error},
    }

    tasks: dict[str, dict[str, Any]] = {}
    for task_id, kind in ((DAILY_TASK_ID, "daily"), (REVIEW_TASK_ID, "review")):
        entry: dict[str, Any] = {"present": False}
        if scheduler is not None:
            try:
                task = scheduler.get_task(task_id)
            except Exception:
                logger.debug("[Newsroom] status: get_task(%s) failed", task_id, exc_info=True)
                task = None
            if task is not None:
                meta = task.metadata if isinstance(task.metadata, dict) else {}
                version = int(meta.get("prompt_version") or 0)
                entry = {
                    "present": True,
                    "enabled": bool(task.enabled),
                    "cron": (task.trigger_config or {}).get("cron", ""),
                    "silent": bool(getattr(task, "silent", False)),
                    "prompt_drift": bool(meta.get("prompt_drift")),
                    "prompt_version": version,
                    "prompt_version_current": version >= PROMPT_VERSION,
                }
        tasks[kind] = entry
    payload["tasks"] = tasks

    try:
        rows = contract.list_issues(limit=1)
        if rows:
            day = rows[0]["issue_date"]
            manifest = contract.read_manifest(day)
            payload["last_issue"] = {
                "date": day,
                "status": rows[0]["status"],
                "title": rows[0].get("title", ""),
                "delivered": bool(manifest.delivered_at) if manifest else False,
                "feishu_doc_url": rows[0].get("feishu_doc_url", ""),
            }
        else:
            payload["last_issue"] = None
    except Exception as exc:
        payload["last_issue"] = {"error": str(exc)}

    try:
        snapshot = load_latest_proposal()
        payload["proposal"] = {
            "status": snapshot.status,
            "remaining_ops": len(snapshot.remaining_op_ids()) if snapshot.proposal else 0,
        }
    except Exception as exc:
        payload["proposal"] = {"error": str(exc)}

    try:
        records = await feedback.get_all_feedback()
        item_records = await feedback.get_all_item_feedback()
        payload["feedback"] = {
            "up": sum(1 for r in records if r["rating"] > 0),
            "down": sum(1 for r in records if r["rating"] < 0),
            "item_up": sum(1 for r in item_records if r["rating"] > 0),
            "item_down": sum(1 for r in item_records if r["rating"] < 0),
        }
    except Exception as exc:
        payload["feedback"] = {"error": str(exc)}

    try:
        window = cfg.issue_history_days
        seen = collect_seen_urls(before_date=date.today().isoformat(), days=window)
        payload["dedup_pool"] = {"window_days": window, "seen_urls": len(seen)}
    except Exception as exc:
        payload["dedup_pool"] = {"error": str(exc)}

    return payload

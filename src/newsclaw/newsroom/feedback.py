"""逐期人工反馈存储（data/newsroom/feedback.db）。

与 api/routes/feedback_store.py 的用户反馈库同构但独立：每期一行
（issue_date 为主键，后写覆盖先写），写入时同步做两件事：

1. 导出只读快照 ``feedback-export.json`` —— 管线 / 复盘任务用 read_file
   消费，Agent 无需访问 HTTP；
2. 回写当期 manifest 的 ``feedback`` 字段 —— list_issues 聚合视图因此
   无需联表。

本模块是纯存储层：校验失败抛 ValueError，数据库不可用抛
storage.safe_sqlite.SQLiteUnavailable，HTTP 语义由路由层翻译。
"""

from __future__ import annotations

import json
import logging
import os
import time
from pathlib import Path

import aiosqlite

from newsclaw.newsroom.contract import newsroom_root, read_manifest, write_manifest

logger = logging.getLogger(__name__)

_EXPORT_FILENAME = "feedback-export.json"
_VALID_RATINGS = (-1, 0, 1)  # 点踩 / 仅留言 / 点赞


def _db_path() -> Path:
    path = newsroom_root() / "feedback.db"
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def export_path() -> Path:
    return newsroom_root() / _EXPORT_FILENAME


def ensure_feedback_export() -> Path:
    """首期写一份空的 feedback-export.json，避免管线把「还没反馈」当成读盘失败。"""
    path = export_path()
    if path.is_file():
        return path
    payload = {
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "summary": {"total": 0, "up": 0, "down": 0},
        "issues": {},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)
    return path


async def _get_conn() -> aiosqlite.Connection:
    """打开带加固的连接并确保表存在（每次调用独立连接，调用方负责关闭）。"""
    from newsclaw.storage.safe_sqlite import safe_open_async

    conn = await safe_open_async(
        _db_path(),
        want_wal=True,
        run_quick_check=False,
        foreign_keys=False,
        row_factory=aiosqlite.Row,
    )
    try:
        await conn.execute(
            """CREATE TABLE IF NOT EXISTS newsroom_feedback (
                   issue_date TEXT PRIMARY KEY,
                   rating     INTEGER NOT NULL,
                   comment    TEXT NOT NULL DEFAULT '',
                   updated_at TEXT NOT NULL
               )"""
        )
        await conn.commit()
    except Exception:
        await conn.close()
        raise
    return conn


async def set_feedback(issue_date: str, rating: int, comment: str = "") -> dict:
    """写入（覆盖）某期反馈，并刷新导出快照与 manifest 摘要。返回该期反馈。"""
    if rating not in _VALID_RATINGS:
        raise ValueError(f"rating must be one of {_VALID_RATINGS}, got {rating}")
    comment = (comment or "").strip()
    now = time.strftime("%Y-%m-%dT%H:%M:%S%z")

    conn = await _get_conn()
    try:
        await conn.execute(
            """INSERT INTO newsroom_feedback (issue_date, rating, comment, updated_at)
               VALUES (?, ?, ?, ?)
               ON CONFLICT(issue_date) DO UPDATE SET
                 rating = excluded.rating,
                 comment = excluded.comment,
                 updated_at = excluded.updated_at""",
            (issue_date, rating, comment, now),
        )
        await conn.commit()
    finally:
        await conn.close()

    record = {"issue_date": issue_date, "rating": rating, "comment": comment, "updated_at": now}
    await export_feedback()
    _sync_manifest_feedback(issue_date, rating, comment)
    return record


async def get_all_feedback() -> list[dict]:
    """全部反馈按日期倒序（API 直用）。"""
    conn = await _get_conn()
    try:
        cursor = await conn.execute(
            """SELECT issue_date, rating, comment, updated_at
               FROM newsroom_feedback ORDER BY issue_date DESC"""
        )
        rows = await cursor.fetchall()
        return [dict(r) for r in rows]
    finally:
        await conn.close()


async def export_feedback() -> Path:
    """把反馈库导出为 feedback-export.json（含汇总统计），原子写入。"""
    records = await get_all_feedback()
    rated = [r["rating"] for r in records if r["rating"] != 0]
    payload = {
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "summary": {
            "total": len(records),
            "up": sum(1 for r in rated if r > 0),
            "down": sum(1 for r in rated if r < 0),
        },
        "issues": {r["issue_date"]: r for r in records},
    }
    path = export_path()
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)
    return path


def _sync_manifest_feedback(issue_date: str, rating: int, comment: str) -> None:
    """把反馈摘要回写进当期 manifest（manifest 不存在则静默跳过）。"""
    manifest = read_manifest(issue_date)
    if manifest is None:
        return
    manifest.feedback = {"rating": rating, "comment": comment}
    if rating < 0 and manifest.status == "ready":
        # 负反馈后不能继续冒充 ready；降为 rejected，契约才能回写。
        manifest.status = "rejected"
    try:
        write_manifest(manifest)
    except (ValueError, OSError) as e:
        logger.warning("[Newsroom] manifest feedback sync failed for %s: %s", issue_date, e)

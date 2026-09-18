"""逐期人工反馈存储（data/newsroom/feedback.db）。

与 api/routes/feedback_store.py 的用户反馈库同构但独立，含两级粒度：

- **期级**（``newsroom_feedback``，issue_date 主键，后写覆盖）：一票定夺，
  负反馈把 ready 降为 rejected、正向改评可恢复（见 ``_sync_manifest_feedback``）；
- **条目级**（``newsroom_item_feedback``，issue_date + item_url 主键）：
  对 manifest.items 单条的点赞/点踩。**仅供周复盘做证据，不参与 ready
  判定、不改变期次状态**——条目差评的出口是复盘提案（换信源/加排除词），
  不是把整期拉下马。

写入时同步导出只读快照 ``feedback-export.json``（管线 / 复盘任务用
read_file 消费，Agent 无需访问 HTTP），并把期级摘要回写当期 manifest 的
``feedback`` 字段——list_issues 聚合视图因此无需联表。

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
        await conn.execute(
            """CREATE TABLE IF NOT EXISTS newsroom_item_feedback (
                   issue_date TEXT NOT NULL,
                   item_url   TEXT NOT NULL,
                   rating     INTEGER NOT NULL,
                   comment    TEXT NOT NULL DEFAULT '',
                   updated_at TEXT NOT NULL,
                   PRIMARY KEY (issue_date, item_url)
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
    """全部期级反馈按日期倒序（API 直用）。"""
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


async def set_item_feedback(issue_date: str, item_url: str, rating: int, comment: str = "") -> dict:
    """写入（覆盖）某期单条素材的反馈。仅供复盘参考，不触碰期次状态。"""
    if rating not in _VALID_RATINGS:
        raise ValueError(f"rating must be one of {_VALID_RATINGS}, got {rating}")
    url = (item_url or "").strip()
    if not url:
        raise ValueError("item_url must not be empty")
    comment = (comment or "").strip()
    now = time.strftime("%Y-%m-%dT%H:%M:%S%z")

    conn = await _get_conn()
    try:
        await conn.execute(
            """INSERT INTO newsroom_item_feedback
                   (issue_date, item_url, rating, comment, updated_at)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(issue_date, item_url) DO UPDATE SET
                 rating = excluded.rating,
                 comment = excluded.comment,
                 updated_at = excluded.updated_at""",
            (issue_date, url, rating, comment, now),
        )
        await conn.commit()
    finally:
        await conn.close()

    await export_feedback()
    return {
        "issue_date": issue_date,
        "url": url,
        "rating": rating,
        "comment": comment,
        "updated_at": now,
    }


async def get_all_item_feedback() -> list[dict]:
    """全部条目反馈按日期倒序、同期按 URL 序（API / 导出共用）。"""
    conn = await _get_conn()
    try:
        cursor = await conn.execute(
            """SELECT issue_date, item_url, rating, comment, updated_at
               FROM newsroom_item_feedback
               ORDER BY issue_date DESC, item_url ASC"""
        )
        rows = await cursor.fetchall()
        return [dict(r) for r in rows]
    finally:
        await conn.close()


async def export_feedback() -> Path:
    """把两级反馈导出为 feedback-export.json（含汇总统计），原子写入。

    ``issues[date].items`` 是该期的条目级反馈；只有条目反馈、尚无期级
    反馈的期次也会出现（rating 记 0），复盘不会漏看。
    """
    records = await get_all_feedback()
    item_records = await get_all_item_feedback()
    rated = [r["rating"] for r in records if r["rating"] != 0]
    item_rated = [r["rating"] for r in item_records if r["rating"] != 0]

    items_by_issue: dict[str, list[dict]] = {}
    for row in item_records:
        items_by_issue.setdefault(row["issue_date"], []).append(
            {
                "url": row["item_url"],
                "rating": row["rating"],
                "comment": row["comment"],
                "updated_at": row["updated_at"],
            }
        )

    issues_payload: dict[str, dict] = {}
    for record in records:
        day = record["issue_date"]
        issues_payload[day] = {**record, "items": items_by_issue.pop(day, [])}
    for day, items in sorted(items_by_issue.items()):
        issues_payload[day] = {
            "issue_date": day,
            "rating": 0,
            "comment": "",
            "updated_at": "",
            "items": items,
        }

    payload = {
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "summary": {
            "total": len(records),
            "up": sum(1 for r in rated if r > 0),
            "down": sum(1 for r in rated if r < 0),
            "item_total": len(item_records),
            "item_up": sum(1 for r in item_rated if r > 0),
            "item_down": sum(1 for r in item_rated if r < 0),
        },
        "issues": issues_payload,
    }
    path = export_path()
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)
    return path


def _try_write_manifest(manifest, issue_date: str) -> None:
    try:
        write_manifest(manifest)
    except (ValueError, OSError) as e:
        logger.warning("[Newsroom] manifest feedback sync failed for %s: %s", issue_date, e)


def _sync_manifest_feedback(issue_date: str, rating: int, comment: str) -> None:
    """把反馈摘要回写进当期 manifest（manifest 不存在则静默跳过）。

    rating < 0：ready 降为 rejected（负反馈后不能继续冒充 ready）。
    rating >= 0 且当前 rejected：解除人工否决——先按 ready 机验恢复，
    过不了就退 partial；不再让 rejected 成为没有出路的一锤子状态。
    """
    manifest = read_manifest(issue_date)
    if manifest is None:
        return
    manifest.feedback = {"rating": rating, "comment": comment}
    if rating < 0:
        if manifest.status == "ready":
            manifest.status = "rejected"
        _try_write_manifest(manifest, issue_date)
        return
    if manifest.status == "rejected":
        for status in ("ready", "partial"):
            manifest.status = status
            try:
                write_manifest(manifest)
                return
            except (ValueError, OSError) as e:
                logger.info(
                    "[Newsroom] feedback restore to %s failed for %s: %s",
                    status,
                    issue_date,
                    e,
                )
        return
    _try_write_manifest(manifest, issue_date)

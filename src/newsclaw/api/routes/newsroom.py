"""AI 早报主线 API（/api/newsroom/*）。

只做三件事：读期次与产物（contract）、读写配置与信源（config/sources）、
收反馈与手动触发（feedback/scheduler）。业务规则全部留在 newsroom 包内，
路由层只做参数校验与错误翻译，不出现存储细节。
"""

from __future__ import annotations

import logging

import yaml
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from newsclaw.newsroom import contract, feedback
from newsclaw.newsroom.config import NewsroomConfig, load_config, save_config
from newsclaw.newsroom.seed import DAILY_TASK_ID, ensure_newsroom_tasks
from newsclaw.newsroom.sources import SourceBook, save_sources, sources_path

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/newsroom", tags=["AI 早报"])


# ── 请求 / 响应模型 ─────────────────────────────────────────────────


class FeedbackBody(BaseModel):
    issue_date: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    rating: int = Field(ge=-1, le=1, description="1 点赞 / 0 仅留言 / -1 点踩")
    comment: str = Field(default="", max_length=500)


class ConfigBody(BaseModel):
    enabled: bool | None = None
    daily_cron: str | None = None
    review_cron: str | None = None
    obsidian_vault: str | None = None
    issue_history_days: int | None = Field(default=None, ge=1, le=30)
    task_timeout_seconds: int | None = Field(default=None, ge=300, le=14400)


class SourcesBody(BaseModel):
    """信源以 YAML 文本透传：WebUI、复盘任务与磁盘文件共用同一方言。"""

    yaml: str = Field(min_length=1, max_length=100_000)


def _cron_is_valid(expression: str) -> bool:
    """5 段 cron 的最低限度校验（段数与通配符形态）。"""
    parts = expression.split()
    return len(parts) == 5 and all(p for p in parts)


# ── 期次与产物 ──────────────────────────────────────────────────────


@router.get("/issues")
async def list_issues(limit: int = 60):
    return {"issues": contract.list_issues(limit=limit)}


@router.get("/issues/{issue_date}")
async def get_issue(issue_date: str):
    manifest = contract.read_manifest(issue_date)
    content = contract.read_issue_content(issue_date)
    if manifest is None and content is None:
        return JSONResponse(status_code=404, content={"error": "issue not found"})
    return {"manifest": manifest.to_dict() if manifest else None, "content": content}


# ── 配置与信源 ──────────────────────────────────────────────────────


@router.get("/config")
async def get_config():
    return load_config().to_dict()


@router.put("/config")
async def put_config(body: ConfigBody, request: Request):
    current = load_config()
    updates = body.model_dump(exclude_none=True)
    for key in ("daily_cron", "review_cron"):
        if key in updates and not _cron_is_valid(updates[key]):
            return JSONResponse(status_code=422, content={"error": f"invalid cron: {updates[key]}"})
    merged = NewsroomConfig(**{**current.to_dict(), **updates})
    save_config(merged)

    # 排期契约以 config.yaml 为准：保存后立即回写任务，不必等下次启动
    from newsclaw.scheduler import get_active_scheduler

    scheduler = get_active_scheduler()
    if scheduler is not None:
        try:
            await ensure_newsroom_tasks(scheduler)
        except Exception:
            logger.exception("[Newsroom] task reconcile after config save failed")
    return merged.to_dict()


@router.get("/sources")
async def get_sources():
    """返回信源清单原文（YAML），前端直编后原样 PUT 回。"""
    return {"yaml": sources_path().read_text(encoding="utf-8")}


@router.put("/sources")
async def put_sources(body: SourcesBody):
    try:
        parsed = yaml.safe_load(body.yaml)
    except yaml.YAMLError as e:
        return JSONResponse(status_code=422, content={"error": f"invalid YAML: {e}"})
    if not isinstance(parsed, dict):
        return JSONResponse(status_code=422, content={"error": "sources must be a YAML mapping"})
    try:
        save_sources(SourceBook.from_dict(parsed))
    except ValueError as e:
        return JSONResponse(status_code=422, content={"error": str(e)})
    return {"yaml": sources_path().read_text(encoding="utf-8")}


# ── 反馈与手动触发 ──────────────────────────────────────────────────


@router.get("/feedback")
async def get_feedback():
    records = await feedback.get_all_feedback()
    summary = {"up": 0, "down": 0, "notes": 0}
    for r in records:
        if r["rating"] > 0:
            summary["up"] += 1
        elif r["rating"] < 0:
            summary["down"] += 1
        else:
            summary["notes"] += 1
    return {"summary": summary, "records": records}


@router.post("/feedback")
async def post_feedback(body: FeedbackBody):
    manifest = contract.read_manifest(body.issue_date)
    if manifest is None:
        return JSONResponse(status_code=404, content={"error": "issue not found"})
    try:
        record = await feedback.set_feedback(body.issue_date, body.rating, body.comment)
    except ValueError as e:
        return JSONResponse(status_code=422, content={"error": str(e)})
    return record


@router.post("/generate")
async def generate_now(request: Request):
    """立即触发每日管线（后台执行，返回 execution_id 供前端轮询调度记录）。"""
    from newsclaw.scheduler import get_active_scheduler

    scheduler = get_active_scheduler()
    if scheduler is None:
        return JSONResponse(status_code=503, content={"error": "Agent not initialized"})
    if not load_config().enabled:
        return JSONResponse(status_code=409, content={"error": "newsroom disabled in config"})
    if scheduler.get_task(DAILY_TASK_ID) is None:
        return JSONResponse(status_code=409, content={"error": "daily task not seeded yet"})
    execution_id = scheduler.trigger_in_background(DAILY_TASK_ID)
    if execution_id is None:
        return JSONResponse(status_code=500, content={"error": "trigger failed"})
    return JSONResponse(
        status_code=202, content={"status": "accepted", "execution_id": execution_id}
    )

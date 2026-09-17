"""newsroom 定时任务的幂等播种。

沿用主干 ``system:daily_selfcheck`` 的播种习语（固定任务 ID + 存在性检查 +
契约修复），但把"配置唯一来源"显式化为 data/newsroom/config.yaml：

- ``enabled`` 与两条 cron 每次 ensure 都以 config.yaml 为准回写任务
  （在 GUI 调度面板里手改这两项会在下次启动时被拉回，config.yaml 头部有说明）；
- prompt 只在 ``metadata.prompt_version`` 落后于 ``prompts.PROMPT_VERSION``
  时刷新，避免每次启动覆盖用户在 GUI 里对 prompt 的手动微调。
- 每日任务的「当期信源 / 方针」不依赖这份缓存字符串：调度触发时由
  executor 调用 ``with_runtime_injection`` 现读磁盘再拼，apply 后下次
  执行就能读到新状态。

调度器单例由主 Agent 初始化后才可用（serve 模式下晚于 FastAPI startup），
因此 :func:`start_background_seeding` 以轮询方式等待，server.py 只需挂一行。
"""

from __future__ import annotations

import asyncio
import logging

from newsclaw.newsroom.config import config_path, load_config, save_config
from newsclaw.newsroom.prompts import PROMPT_VERSION, build_daily_prompt, build_review_prompt
from newsclaw.newsroom.sources import load_sources

logger = logging.getLogger(__name__)

DAILY_TASK_ID = "newsroom_daily_pipeline"
REVIEW_TASK_ID = "newsroom_weekly_review"

_POLL_INTERVAL = 5.0
_POLL_TIMEOUT = 600.0

_seeding_started = False


async def ensure_newsroom_tasks(scheduler) -> bool:
    """按 config.yaml 创建 / 修正两条任务。返回是否有变更（均已持久化）。

    同时承担首次初始化：保证 data/newsroom/ 下的 config.yaml 与
    sources.yaml 存在（load_sources 首次调用即落盘默认清单）。
    """
    cfg = load_config()
    if not config_path().is_file():
        save_config(cfg)  # 首次运行落盘默认配置，用户可发现并编辑
    load_sources()  # 首次调用生成默认信源清单

    from newsclaw.scheduler.task import (
        ScheduledTask,
        TaskDeliveryPolicy,
        TaskSource,
        TaskType,
        TriggerType,
    )

    changed = False
    specs = (
        (
            DAILY_TASK_ID,
            "AI 早报·每日管线（config.yaml 管理）",
            "采集 AI 圈新闻，产出小红书/公众号/日报三产物并沉淀 Wiki、自评落账",
            cfg.daily_cron,
            build_daily_prompt(cfg),
            "daily",
        ),
        (
            REVIEW_TASK_ID,
            "AI 早报·每周复盘（config.yaml 管理）",
            "复盘近 7 期自评与人工反馈，迭代信源清单、编辑方针与记忆规则",
            cfg.review_cron,
            build_review_prompt(cfg),
            "review",
        ),
    )
    for task_id, name, description, cron, prompt, kind in specs:
        existing = scheduler.get_task(task_id)
        if existing is None:
            task = ScheduledTask(
                id=task_id,
                name=name,
                description=description,
                trigger_type=TriggerType.CRON,
                trigger_config={"cron": cron},
                prompt=prompt,
                task_type=TaskType.TASK,
                task_source=TaskSource.SYSTEM,
                delivery_policy=TaskDeliveryPolicy.OWNER_ONLY,
                agent_profile_id="ai-news-editor",
                no_schedule_tools=True,
                silent=True,
                deletable=False,
                enabled=cfg.enabled,
                metadata={
                    "newsroom": kind,
                    "prompt_version": PROMPT_VERSION,
                    "timeout_seconds": cfg.task_timeout_seconds,
                },
            )
            await scheduler.add_task(task)
            changed = True
            logger.info(
                "[Newsroom] seeded task %s (cron=%s, enabled=%s)", task_id, cron, cfg.enabled
            )
            continue

        updates: dict = {}
        if existing.trigger_config.get("cron") != cron:
            updates["trigger_config"] = {"cron": cron}
        if existing.enabled != cfg.enabled:
            updates["enabled"] = cfg.enabled
        metadata = dict(existing.metadata or {})
        meta_changed = False
        if metadata.get("newsroom") != kind:
            metadata["newsroom"] = kind
            meta_changed = True
        if metadata.get("timeout_seconds") != cfg.task_timeout_seconds:
            metadata["timeout_seconds"] = cfg.task_timeout_seconds
            meta_changed = True
        if metadata.get("prompt_version", 0) < PROMPT_VERSION:
            updates["prompt"] = prompt
            updates["description"] = description
            metadata["prompt_version"] = PROMPT_VERSION
            meta_changed = True
        if meta_changed:
            updates["metadata"] = metadata
        if updates:
            await scheduler.update_task(task_id, updates)
            changed = True
            logger.info("[Newsroom] reconciled task %s: %s", task_id, sorted(updates))

    if changed:
        await scheduler.save()
    return changed


def start_background_seeding() -> asyncio.Task | None:
    """在服务启动后等待调度器就绪并播种（幂等，可安全重复调用）。

    返回后台 asyncio.Task（调用方可丢弃）；已在运行或事件循环不可用时
    返回 None。
    """
    global _seeding_started
    if _seeding_started:
        return None

    async def _run() -> None:
        from newsclaw.scheduler import get_active_scheduler

        global _seeding_started
        waited = 0.0
        scheduler = get_active_scheduler()
        while scheduler is None and waited < _POLL_TIMEOUT:
            await asyncio.sleep(_POLL_INTERVAL)
            waited += _POLL_INTERVAL
            scheduler = get_active_scheduler()
        if scheduler is None:
            logger.warning(
                "[Newsroom] scheduler not ready after %.0fs; skip seeding this boot",
                _POLL_TIMEOUT,
            )
            _seeding_started = False
            return
        try:
            await ensure_newsroom_tasks(scheduler)
        except Exception:
            logger.exception("[Newsroom] seeding failed")
            _seeding_started = False

    try:
        task = asyncio.get_running_loop().create_task(_run())
    except RuntimeError:
        return None
    _seeding_started = True
    return task

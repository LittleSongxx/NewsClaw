"""本地 Wiki 写入工具（NewsClaw 自研）。

为什么是**工具**而不是让 Agent 直接 write_file：知识库的结构（frontmatter、
按日期的章节、要点格式、互链、MOC）如果交给模型自由书写，几天后必然出现格式
漂移与重复章节。这个工具把结构收敛为确定行为，Agent 只负责"提供要点与来源"。

写入是幂等的：同一天重复调用 = 替换该日期章节。
"""

import json
import logging
from typing import Any

from ...core.policy_v2.enums import ApprovalClass
from ...wiki import store

logger = logging.getLogger(__name__)


class WikiHandler:
    TOOLS = ["wiki_upsert"]
    # 显式风险等级（与 write_file 同档：写本地文件、作用域受限于 wiki 根目录）。
    # 不声明会落到 UNKNOWN → 矩阵一律 CONFIRM → 无人值守的定时任务默认拒绝，
    # 表现为"决策了却没执行"（feishu_doc 踩过同一个坑）。
    TOOL_CLASSES = {"wiki_upsert": ApprovalClass.MUTATING_SCOPED}

    def __init__(self, agent: Any = None) -> None:
        self.agent = agent

    async def handle(self, tool_name: str, params: dict) -> str:
        page = str(params.get("page") or "").strip()
        if not page:
            return json.dumps({"ok": False, "error": "page 不能为空"}, ensure_ascii=False)

        kind = str(params.get("kind") or "topic").strip().lower()
        if kind not in ("topic", "company"):
            return json.dumps(
                {"ok": False, "error": "kind 只能是 topic（主题页）或 company（公司页）"},
                ensure_ascii=False,
            )

        day = str(params.get("day") or "").strip()
        if not day:
            return json.dumps({"ok": False, "error": "day 必填（YYYY-MM-DD）"}, ensure_ascii=False)
        try:
            from datetime import date as _date

            _date.fromisoformat(day)
        except ValueError:
            return json.dumps(
                {"ok": False, "error": f"day 必须是 YYYY-MM-DD：{day}"},
                ensure_ascii=False,
            )

        raw_entries = params.get("entries") or []
        entries: list[store.WikiEntry] = []
        for item in raw_entries:
            if isinstance(item, str):
                entries.append(store.WikiEntry(text=item))
            elif isinstance(item, dict):
                entries.append(
                    store.WikiEntry(
                        text=str(item.get("text") or ""),
                        source=str(item.get("source") or ""),
                    )
                )
        links = [str(x) for x in (params.get("links") or []) if str(x).strip()]
        summary = str(params.get("summary") or "").strip()

        if not store.wiki_enabled():
            return json.dumps(
                {
                    "ok": False,
                    "skipped": True,
                    "error": "obsidian_vault 未配置，已跳过 Wiki 写入",
                },
                ensure_ascii=False,
            )

        try:
            result = store.upsert_daily_section(
                page,
                kind=kind,
                day=day,
                summary=summary,
                entries=entries,
                links=links,
            )
            moc = store.rebuild_moc()
            result["moc"] = str(moc.relative_to(store.wiki_root()))
            result["ok"] = True
        except store.WikiDisabledError as exc:
            return json.dumps(
                {"ok": False, "skipped": True, "error": str(exc)}, ensure_ascii=False
            )
        except ValueError as exc:  # 页面名非法等用户可纠正的问题
            return json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)
        except OSError as exc:
            logger.exception("[wiki_upsert] 写入失败")
            return json.dumps(
                {"ok": False, "error": f"写入失败（{type(exc).__name__}）：{exc}"},
                ensure_ascii=False,
            )

        logger.info("[wiki_upsert] %s → %s（%d 条要点）", page, result["path"], len(entries))
        return json.dumps(result, ensure_ascii=False)


def create_handler(agent: Any = None):
    """返回 bound method —— 注册表通过 ``__self__`` 读取 TOOLS/TOOL_CLASSES。"""
    return WikiHandler(agent).handle

"""本地 Wiki 知识库（NewsClaw 自研）。

设计目标：把每期调研结果沉淀为**结构化、可浏览、可检索**的本地 Markdown 知识
库——既是项目前端的 Wiki 视图的数据源，也是标准 Obsidian vault（同一份文件，
两种专业形态，不需要搬运或转换）。

    store.py   页面读写：主题/公司原子页、按日期的幂等章节、MOC 索引、反向链接

对外入口（供 API 路由与 wiki_upsert 工具复用）::

    from newsclaw.wiki import store
    store.upsert_daily_section("大模型", kind="topic", day="2026-09-17", ...)
    store.list_pages() / store.read_page("大模型") / store.backlinks("大模型")
"""

from .store import (
    MOC_FILENAME,
    TOPIC_PAGES,
    PageInfo,
    WikiDisabledError,
    WikiEntry,
    backlinks,
    graph_data,
    list_pages,
    read_page,
    rebuild_moc,
    sanitize_page_name,
    search,
    topic_pages,
    upsert_daily_section,
    wiki_enabled,
    wiki_root,
)

__all__ = [
    "MOC_FILENAME",
    "TOPIC_PAGES",
    "PageInfo",
    "WikiDisabledError",
    "WikiEntry",
    "backlinks",
    "graph_data",
    "list_pages",
    "read_page",
    "rebuild_moc",
    "sanitize_page_name",
    "search",
    "topic_pages",
    "upsert_daily_section",
    "wiki_enabled",
    "wiki_root",
]

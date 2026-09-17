"""本地 Wiki API（/api/wiki/*）。

只做读操作（写入由 wiki_upsert 工具在管线里完成，保证格式统一）：
    GET /api/wiki/root     库根路径与统计（前端展示"知识库位置"）
    GET /api/wiki/pages    页面列表（含日期、出链、摘要），前端建主题树
    GET /api/wiki/page     单页正文 + 反向链接（?name=大模型 或 ?path=主题/大模型.md）
    GET /api/wiki/search   全文搜索（子串匹配，量级小无需索引）

页面名可能含中文与空格，统一走查询参数而不是路径参数，避免编码歧义。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from ...wiki import store

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/wiki", tags=["本地 Wiki"])


@router.get("/root")
async def wiki_info():
    root = store.wiki_root()
    pages = store.list_pages()
    return {
        "root": str(root),
        "exists": root.is_dir(),
        "page_count": len([p for p in pages if p.kind != "index"]),
        "topic_count": len([p for p in pages if p.kind == "topic"]),
        "company_count": len([p for p in pages if p.kind == "company"]),
        "topics": list(store.TOPIC_PAGES),
    }


@router.get("/pages")
async def wiki_pages():
    pages = store.list_pages()
    return {
        "pages": [
            {
                "name": p.name,
                "path": p.path,
                "kind": p.kind,
                "updated_at": p.updated_at,
                "dates": p.dates,
                "links": p.links,
                "excerpt": p.excerpt,
            }
            for p in pages
        ]
    }


@router.get("/page")
async def wiki_page(name: str = "", path: str = ""):
    if not name and not path:
        return JSONResponse(status_code=422, content={"error": "需要 name 或 path 参数"})
    key = name or path
    content = store.read_page(key)
    if content is None:
        return JSONResponse(status_code=404, content={"error": f"页面不存在：{key}"})

    # 解析出规范名与相对路径（name 与 path 两种入参都支持）
    pages = store.list_pages()
    matched = next((p for p in pages if p.name == key or p.path == key), None)
    page_name = matched.name if matched else key
    return {
        "name": page_name,
        "path": matched.path if matched else "",
        "content": content,
        "backlinks": store.backlinks(page_name),
    }


@router.get("/graph")
async def wiki_graph(days: int | None = None):
    """知识网络：节点=页面，边=[[双链]]（供前端力导向图渲染）。

    ``?days=N`` 只看最近 N 天内有更新的页面与关系（观察网络随时间的演化）。
    """
    if days is not None and not 1 <= days <= 3650:
        return JSONResponse(status_code=422, content={"error": "days 需在 1..3650 之间"})
    return store.graph_data(days=days)


@router.get("/search")
async def wiki_search(q: str = "", limit: int = 30):
    return {"query": q, "hits": store.search(q, limit=max(1, min(limit, 100)))}

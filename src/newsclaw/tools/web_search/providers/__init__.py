"""Built-in web_search providers.

Each module in this package registers itself on import:

    bocha       — 博查（国内推荐，需要 BOCHA_API_KEY）
    tavily      — Tavily（首选；多 Key 号池，TAVILY_API_KEY 逗号分隔）
    serper      — Serper/Google（含 news 端点；SERPER_API_KEY 同样支持号池）
    searxng     — 自部署 SearXNG（需要 SEARXNG_BASE_URL）
    jina        — Jina Reader（无 Key 走免费额度，可选 JINA_API_KEY）
    duckduckgo  — DuckDuckGo（无 Key，国内常不可达，作为 auto-detect 兜底）

To add a new provider: drop ``providers/<id>.py`` with a module-level
``register(YourProvider())`` call, then add the import in ``registry._ensure_loaded``.
"""

from __future__ import annotations

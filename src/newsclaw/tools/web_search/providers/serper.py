"""Serper provider — Google 搜索结果（含独立 news 端点）。

API: ``POST https://google.serper.dev/search`` / ``POST https://google.serper.dev/news``
Docs: https://serper.dev/docs

认证走 ``X-API-KEY`` 头；``SERPER_API_KEY`` 支持逗号/换行分隔多 Key，
行为与 Tavily 号池一致（轮询 + 失效冷却），实现共用
:class:`~newsclaw.tools.web_search.providers.tavily.KeyPool`。

Auto-detect priority: 15（Tavily 之后的第二梯队国内可达源）。
"""

from __future__ import annotations

import logging
from typing import Any

from ....config import settings
from ..base import (
    AuthFailedError,
    MissingCredentialError,
    NetworkUnreachableError,
    ProviderError,
    RateLimitedError,
    SearchResult,
)
from ..registry import register
from ._http import describe_httpx_failure, search_httpx_client_kwargs
from .tavily import KeyPool

logger = logging.getLogger(__name__)

_QUOTA_HINT_WORDS = ("exceeded", "limit", "quota", "insufficient", "credit")


def _mask(key: str) -> str:
    return f"...{key[-4:]}" if len(key) > 4 else "***"


class SerperProvider:
    id = "serper"
    label = "Serper (Google)"
    requires_credential = True
    auto_detect_order = 15
    signup_url = "https://serper.dev"
    docs_url = "https://serper.dev/docs"

    _SEARCH_ENDPOINT = "https://google.serper.dev/search"
    _NEWS_ENDPOINT = "https://google.serper.dev/news"

    def __init__(self) -> None:
        self._pool = KeyPool()

    def _keys(self) -> list[str]:
        return KeyPool.parse_keys(settings.serper_api_key or "")

    def is_available(self) -> bool:
        return bool(self._keys())

    async def _post(
        self,
        api_key: str,
        endpoint: str,
        payload: dict[str, Any],
        *,
        timeout_seconds: float,
    ) -> dict[str, Any]:
        """发一次请求并完成 HTTP 语义 → ProviderError 的映射，返回 JSON。"""
        timeout = timeout_seconds if timeout_seconds and timeout_seconds > 0 else 30.0

        import httpx

        try:
            async with httpx.AsyncClient(
                **search_httpx_client_kwargs(timeout=timeout, target_url=endpoint)
            ) as client:
                resp = await client.post(
                    endpoint,
                    json=payload,
                    headers={"X-API-KEY": api_key, "Content-Type": "application/json"},
                )
        except (httpx.ConnectError, httpx.ConnectTimeout, httpx.ReadTimeout) as exc:
            raise NetworkUnreachableError(
                f"serper transport failure: {describe_httpx_failure(exc)}",
                provider_id=self.id,
            ) from exc
        except httpx.HTTPError as exc:
            raise NetworkUnreachableError(
                f"serper HTTP error: {describe_httpx_failure(exc)}",
                provider_id=self.id,
            ) from exc

        body_text = resp.text or ""
        quota_hit = any(word in body_text.lower() for word in _QUOTA_HINT_WORDS)

        if resp.status_code in (401, 403):
            raise AuthFailedError(
                f"serper rejected credential (HTTP {resp.status_code})",
                provider_id=self.id,
            )
        if resp.status_code == 429 or (resp.status_code == 400 and quota_hit):
            raise RateLimitedError(
                f"serper quota/rate limit (HTTP {resp.status_code})",
                provider_id=self.id,
            )
        if resp.status_code >= 400:
            raise NetworkUnreachableError(
                f"serper HTTP {resp.status_code}: {body_text[:200]}",
                provider_id=self.id,
            )

        try:
            return resp.json()
        except ValueError as exc:
            raise NetworkUnreachableError(
                "serper returned non-JSON response",
                provider_id=self.id,
            ) from exc

    @staticmethod
    def _parse_items(items: list[dict[str, Any]]) -> list[SearchResult]:
        out: list[SearchResult] = []
        for item in items:
            out.append(
                SearchResult(
                    title=str(item.get("title") or "无标题"),
                    url=str(item.get("link") or ""),
                    snippet=str(item.get("snippet") or ""),
                    source=str(item.get("source") or ""),
                    date=str(item.get("date") or ""),
                )
            )
        return out

    async def search(
        self,
        query: str,
        *,
        max_results: int = 5,
        region: str = "wt-wt",
        safesearch: str = "moderate",
        timeout_seconds: float = 0.0,
    ) -> list[SearchResult]:
        keys = self._keys()
        if not keys:
            raise MissingCredentialError("SERPER_API_KEY not configured", provider_id=self.id)

        payload = {"q": query, "num": min(max(1, max_results), 100)}
        last_error: ProviderError | None = None
        for api_key in self._pool.order(keys):
            try:
                data = await self._post(
                    api_key, self._SEARCH_ENDPOINT, payload, timeout_seconds=timeout_seconds
                )
                return self._parse_items(data.get("organic") or [])
            except (AuthFailedError, RateLimitedError) as exc:
                last_error = exc
                self._pool.penalize(api_key)
                logger.info(
                    "[serper] key %s rejected (%s); switching to next key in pool",
                    _mask(api_key),
                    type(exc).__name__,
                )
                continue

        assert last_error is not None
        raise last_error

    async def news_search(
        self,
        query: str,
        *,
        max_results: int = 5,
        region: str = "wt-wt",
        safesearch: str = "moderate",
        timelimit: str | None = None,
        timeout_seconds: float = 0.0,
    ) -> list[SearchResult] | None:
        keys = self._keys()
        if not keys:
            raise MissingCredentialError("SERPER_API_KEY not configured", provider_id=self.id)

        payload: dict[str, Any] = {"q": query, "num": min(max(1, max_results), 100)}
        if timelimit:
            # Serper news 的 tbs 值：qdr:d/p/w/m（日/周/月），与内部 timelimit 对齐
            mapping = {"d": "qdr:d", "w": "qdr:w", "m": "qdr:m"}
            if timelimit in mapping:
                payload["tbs"] = mapping[timelimit]

        last_error: ProviderError | None = None
        for api_key in self._pool.order(keys):
            try:
                data = await self._post(
                    api_key, self._NEWS_ENDPOINT, payload, timeout_seconds=timeout_seconds
                )
                return self._parse_items(data.get("news") or [])
            except (AuthFailedError, RateLimitedError) as exc:
                last_error = exc
                self._pool.penalize(api_key)
                logger.info(
                    "[serper] key %s rejected on news (%s); switching next",
                    _mask(api_key),
                    type(exc).__name__,
                )
                continue

        assert last_error is not None
        raise last_error


register(SerperProvider())

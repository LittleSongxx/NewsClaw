"""Tavily provider — 默认搜索源，支持多 Key 号池。

API: ``POST https://api.tavily.com/search``
Docs: https://docs.tavily.com/

号池（NewsClaw）：``TAVILY_API_KEY`` 支持逗号/分号/换行分隔的多个 Key
（单 Key 写法完全兼容）。请求按轮询顺序选 Key；被上游判定失效或超额
（HTTP 401/403/429/432，或响应体含 ``exceeded``/``limit``）的 Key 进入
冷却期，期间不再选用——几个号同时没额度时搜索仍然可用。网络错误会立刻
换下一个 Key，但不做长冷却（同一出口 IP 上所有号都会受影响）。全部冷却
时乐观重试最早到期的一个，避免整池短暂停摆。

冷却游标会落到数据根目录（``NEWSCLAW_ROOT`` / ``~/.newsclaw``）的
``web_search/key_pool.json``，只写 Key 指纹，不写完整密钥。

Auto-detect priority: 5（NewsClaw 默认搜索源）。
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import tempfile
import time
from pathlib import Path
from typing import Any

from ....config import settings
from ....data_root import resolve_data_root
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

logger = logging.getLogger(__name__)

#: 失效/超额 Key 的冷却时长（秒）。额度按小时/天恢复，30 分钟是折中：
#: 既避开已耗尽的号，又不会让一个临时 429 的号闲置太久。
_KEY_COOLDOWN_SECONDS = 1800.0

#: 部分错误以 400/403 + 文本形式返回，按关键字识别为额度问题。
_QUOTA_HINT_WORDS = ("exceeded", "limit", "quota", "insufficient")


def _key_fingerprint(key: str) -> str:
    """号池落盘只用指纹，避免完整 Key 进入数据目录。"""
    return hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]


def _pool_state_path() -> Path:
    return resolve_data_root() / "web_search" / "key_pool.json"


class KeyPool:
    """多 Key 轮询池：可用 Key 优先、冷却 Key 靠后，全冷却时乐观重试最早到期者。

    实例是进程级单例的一部分（provider 模块只 register 一次），状态只有
    游标与冷却表。传入 ``state_name`` 时，冷却会落到数据根目录，重启后仍
    避开刚被 429/401 罚下的号。未命名的池（单测）保持纯内存。
    """

    def __init__(
        self,
        cooldown_seconds: float = _KEY_COOLDOWN_SECONDS,
        *,
        state_name: str | None = None,
    ) -> None:
        self._cooldown = cooldown_seconds
        self._state_name = (state_name or "").strip() or None
        self._cursor = 0
        self._blocked_until: dict[str, float] = {}
        self._fingerprint_until_wall: dict[str, float] = {}
        if self._state_name:
            self._load_state()

    @staticmethod
    def parse_keys(raw: str) -> list[str]:
        """把多 Key 配置拆成列表：逗号/分号/空白/换行分隔，去重保序。"""
        parts = [k.strip() for k in re.split(r"[,;\s]+", raw or "") if k.strip()]
        seen: set[str] = set()
        ordered: list[str] = []
        for key in parts:
            if key not in seen:
                seen.add(key)
                ordered.append(key)
        return ordered

    def _hydrate_keys(self, keys: list[str], *, now_mono: float) -> None:
        """把落盘的墙钟冷却换算成当前进程的 monotonic 截止时间。"""
        if not self._fingerprint_until_wall:
            return
        wall_now = time.time()
        expired: list[str] = []
        for key in keys:
            fp = _key_fingerprint(key)
            until_wall = self._fingerprint_until_wall.get(fp)
            if until_wall is None:
                continue
            remaining = until_wall - wall_now
            if remaining <= 0:
                expired.append(fp)
                self._blocked_until.pop(key, None)
                continue
            if key not in self._blocked_until:
                self._blocked_until[key] = now_mono + remaining
        for fp in expired:
            self._fingerprint_until_wall.pop(fp, None)

    def order(self, keys: list[str], *, now: float | None = None) -> list[str]:
        """返回本次请求的尝试顺序：可用 Key 轮询在前，冷却 Key 在最后。"""
        moment = time.monotonic() if now is None else now
        if self._state_name and now is None:
            self._hydrate_keys(keys, now_mono=moment)
        live = [k for k in keys if self._blocked_until.get(k, 0.0) <= moment]
        cooling = sorted(
            (k for k in keys if self._blocked_until.get(k, 0.0) > moment),
            key=lambda k: self._blocked_until[k],
        )
        if not live and cooling:
            # 全池冷却：乐观重试最早到期者，其余仍排在其后。
            live, cooling = cooling[:1], cooling[1:]
        if live:
            self._cursor = (self._cursor + 1) % len(live)
            live = live[self._cursor :] + live[: self._cursor]
        return live + cooling

    def penalize(self, key: str) -> None:
        """把某个 Key 打入冷却（失效/超额时调用）。"""
        self._blocked_until[key] = time.monotonic() + self._cooldown
        if self._state_name:
            self._fingerprint_until_wall[_key_fingerprint(key)] = time.time() + self._cooldown
            self._save_state()

    def cooling_snapshot(self, keys: list[str]) -> dict[str, float]:
        """调试用：冷却中的 Key（仅尾 4 位）→ 剩余秒数。"""
        now = time.monotonic()
        return {
            f"...{k[-4:]}": round(self._blocked_until.get(k, 0.0) - now, 1)
            for k in keys
            if self._blocked_until.get(k, 0.0) > now
        }

    def _load_state(self) -> None:
        if not self._state_name:
            return
        path = _pool_state_path()
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return
        except Exception as exc:
            logger.debug("[key_pool] failed to load %s: %s", path, exc)
            return
        if not isinstance(raw, dict):
            return
        section = raw.get(self._state_name)
        if not isinstance(section, dict):
            return
        cursor = section.get("cursor")
        if isinstance(cursor, int) and cursor >= 0:
            self._cursor = cursor
        cooling = section.get("cooling")
        if not isinstance(cooling, dict):
            return
        wall_now = time.time()
        for fp, until in cooling.items():
            if not isinstance(fp, str) or not isinstance(until, (int, float)):
                continue
            if until > wall_now:
                self._fingerprint_until_wall[fp] = float(until)

    def _save_state(self) -> None:
        if not self._state_name:
            return
        path = _pool_state_path()
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            data: dict[str, Any] = {}
            if path.exists():
                try:
                    loaded = json.loads(path.read_text(encoding="utf-8"))
                    if isinstance(loaded, dict):
                        data = loaded
                except Exception:
                    data = {}
            wall_now = time.time()
            cooling = {
                fp: until
                for fp, until in self._fingerprint_until_wall.items()
                if until > wall_now
            }
            data[self._state_name] = {"cursor": self._cursor, "cooling": cooling}
            fd, tmp_name = tempfile.mkstemp(prefix="key_pool.", suffix=".json", dir=path.parent)
            tmp_path = Path(tmp_name)
            try:
                with open(fd, "w", encoding="utf-8") as handle:
                    json.dump(data, handle, ensure_ascii=False, indent=2)
                    handle.write("\n")
                tmp_path.replace(path)
            except Exception:
                tmp_path.unlink(missing_ok=True)
                raise
        except Exception as exc:
            logger.debug("[key_pool] failed to persist %s: %s", path, exc)


def _mask(key: str) -> str:
    """日志中只出现 Key 尾 4 位，避免密钥进日志文件。"""
    return f"...{key[-4:]}" if len(key) > 4 else "***"


class TavilyProvider:
    id = "tavily"
    label = "Tavily"
    requires_credential = True
    # NewsClaw：首选搜索源（原值 20=bocha 之后，收敛后提前到最前）。
    auto_detect_order = 5
    signup_url = "https://app.tavily.com/home"
    docs_url = "https://docs.tavily.com/"

    _ENDPOINT = "https://api.tavily.com/search"

    def __init__(self) -> None:
        self._pool = KeyPool(state_name="tavily")

    def _keys(self) -> list[str]:
        return KeyPool.parse_keys(settings.tavily_api_key or "")

    def is_available(self) -> bool:
        return bool(self._keys())

    async def _search_with_key(
        self,
        api_key: str,
        query: str,
        *,
        max_results: int,
        timeout_seconds: float,
    ) -> list[SearchResult]:
        """用单个 Key 发一次请求；HTTP 语义在这里映射为 ProviderError 子类。"""
        payload = {
            "api_key": api_key,
            "query": query,
            "search_depth": "basic",
            "max_results": min(max(1, max_results), 20),
            "include_answer": False,
        }
        timeout = timeout_seconds if timeout_seconds and timeout_seconds > 0 else 30.0

        import httpx

        try:
            async with httpx.AsyncClient(
                **search_httpx_client_kwargs(timeout=timeout, target_url=self._ENDPOINT)
            ) as client:
                resp = await client.post(self._ENDPOINT, json=payload)
        except (httpx.ConnectError, httpx.ConnectTimeout, httpx.ReadTimeout) as exc:
            raise NetworkUnreachableError(
                f"tavily transport failure: {describe_httpx_failure(exc)}",
                provider_id=self.id,
            ) from exc
        except httpx.HTTPError as exc:
            raise NetworkUnreachableError(
                f"tavily HTTP error: {describe_httpx_failure(exc)}",
                provider_id=self.id,
            ) from exc

        body_text = resp.text or ""
        quota_hit = any(word in body_text.lower() for word in _QUOTA_HINT_WORDS)

        if resp.status_code in (401, 403):
            raise AuthFailedError(
                f"tavily rejected credential (HTTP {resp.status_code})",
                provider_id=self.id,
            )
        # 432 是 Tavily 的套餐额度耗尽专用码；部分限额错误以 400/403 + 文本返回。
        if resp.status_code == 429 or (resp.status_code in (400, 432) and quota_hit):
            raise RateLimitedError(
                f"tavily quota/rate limit (HTTP {resp.status_code})",
                provider_id=self.id,
            )
        if resp.status_code >= 400:
            raise NetworkUnreachableError(
                f"tavily HTTP {resp.status_code}: {body_text[:200]}",
                provider_id=self.id,
            )

        try:
            data = resp.json()
        except ValueError as exc:
            raise NetworkUnreachableError(
                "tavily returned non-JSON response",
                provider_id=self.id,
            ) from exc

        results = data.get("results") or []
        out: list[SearchResult] = []
        for item in results:
            out.append(
                SearchResult(
                    title=str(item.get("title") or "无标题"),
                    url=str(item.get("url") or ""),
                    snippet=str(item.get("content") or ""),
                    source=str(item.get("source") or ""),
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
            raise MissingCredentialError("TAVILY_API_KEY not configured", provider_id=self.id)

        last_error: ProviderError | None = None
        for api_key in self._pool.order(keys):
            try:
                return await self._search_with_key(
                    api_key,
                    query,
                    max_results=max_results,
                    timeout_seconds=timeout_seconds,
                )
            except (AuthFailedError, RateLimitedError) as exc:
                # 该 Key 失效或额度耗尽 → 冷却并换号；单号问题不该让搜索整体失败。
                last_error = exc
                self._pool.penalize(api_key)
                logger.info(
                    "[tavily] key %s rejected (%s); switching to next key in pool",
                    _mask(api_key),
                    type(exc).__name__,
                )
                continue
            except NetworkUnreachableError as exc:
                # 网络故障立刻换号，但不做长冷却：同一出口上所有号都会受影响。
                last_error = exc
                logger.info(
                    "[tavily] key %s network error (%s); switching to next key in pool",
                    _mask(api_key),
                    type(exc).__name__,
                )
                continue

        assert last_error is not None  # keys 非空时循环必然执行并赋值
        raise last_error

    async def news_search(self, *args: Any, **kwargs: Any) -> list[SearchResult] | None:
        # Tavily 没有独立的 news 端点；返回 None 让 runtime 换下一家
        return None


register(TavilyProvider())

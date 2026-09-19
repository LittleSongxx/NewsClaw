"""网关重排客户端：DashScope 原生 text-rerank 协议（兼容百炼 MaaS 网关）。

定位：检索两阶段架构中的第二阶段——混合召回 + RRF 融合之后的 cross-encoder
精排。它对 query 与文档做联合建模，给出统一量纲的相关性分数，用于覆写
各召回通道不可比的原始分（cosine / BM25 / 手工常数）。

设计原则 fail-open：重排是锦上添花而非依赖项，任何失败（网络/超时/404）
都返回 None，调用方回退纯公式排序，热路径永不阻塞。
"""

import logging
import threading

logger = logging.getLogger(__name__)


class GatewayReranker:
    """Cross-encoder 重排客户端。

    协议：POST {host}/api/v1/services/rerank/text-rerank/text-rerank
    （host 由 embedding 网关 base_url 剥掉 /compatible-mode/v1 等后缀得到；
    该路径已在百炼 MaaS 网关上验证，Cohere 风格的 /rerank 反而 404。）
    """

    TIMEOUT_SECONDS = 2.5

    def __init__(self, base_url: str, api_key: str, model: str) -> None:
        self._host = self._derive_host(base_url)
        self._api_key = api_key
        self._model = model
        self._httpx = None
        # 端点可用性结论缓存：404 后不再重试（避免热路径反复撞墙）
        self._probe_ok: bool | None = None
        self._probe_lock = threading.Lock()

    @staticmethod
    def _derive_host(base_url: str) -> str:
        """从 embedding 网关 base_url 推导 rerank 端点的 host。"""
        base = (base_url or "").strip().rstrip("/")
        for suffix in ("/compatible-mode/v1", "/compatible-mode", "/v1"):
            if base.endswith(suffix):
                base = base[: -len(suffix)]
                break
        return base

    def rerank(self, query: str, documents: list[str]) -> list[float] | None:
        """返回与 documents 等长的相关性分数列表（已 clamp 到 [0,1]）。

        失败/超时/端点不支持返回 None，调用方保持原有 relevance 不变。
        """
        if not query.strip() or not documents:
            return None
        if self._probe_ok is False:
            return None
        try:
            if self._httpx is None:
                import httpx

                self._httpx = httpx
            url = f"{self._host}/api/v1/services/rerank/text-rerank/text-rerank"
            headers = {
                "Authorization": f"Bearer {self._api_key}",
                "Content-Type": "application/json",
            }
            payload = {
                "model": self._model,
                "input": {"query": query, "documents": documents},
                "parameters": {"return_documents": False, "top_n": len(documents)},
            }
            resp = self._httpx.post(url, json=payload, headers=headers, timeout=self.TIMEOUT_SECONDS)
            if resp.status_code == 404:
                with self._probe_lock:
                    self._probe_ok = False
                logger.warning("[Reranker] 网关不支持 rerank 端点，已自动停用（公式排序兜底）")
                return None
            resp.raise_for_status()
            results = resp.json().get("output", {}).get("results", [])
            scores = [0.0] * len(documents)
            for item in results:
                idx = int(item.get("index", -1))
                if 0 <= idx < len(documents):
                    scores[idx] = max(0.0, min(1.0, float(item.get("relevance_score", 0.0))))
            self._probe_ok = True
            return scores
        except Exception as e:
            logger.debug(f"[Reranker] rerank failed (fallback to formula): {e}")
            return None

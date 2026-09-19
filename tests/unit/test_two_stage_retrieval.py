"""两阶段检索单元测试：RRF 融合、cross-encoder 精排、embedding 客户端与同步透传。"""

from unittest.mock import MagicMock

import pytest

from newsclaw.memory.rerank import GatewayReranker
from newsclaw.memory.retrieval import RetrievalCandidate, RetrievalEngine
from newsclaw.memory.search_backends import (
    ChromaDBBackend,
    EmbeddingAPIClient,
)
from newsclaw.memory.unified_store import UnifiedStore

# ============================================================================
# RRF 融合（UnifiedStore.search_semantic_scored）
# ============================================================================


def _make_mem(mid: str) -> dict:
    """最小记忆字典：scope 四元组与 user 场景对齐，无失效字段。"""
    return {
        "id": mid,
        "content": f"content-{mid}",
        "scope": "user",
        "scope_owner": "",
        "user_id": "default",
        "workspace_id": "default",
    }


def _make_store(tmp_path, primary_results, fts_results):
    """构造双通道可控的 UnifiedStore（真实 tmp 库 + mock 主后端与 FTS5 兜底）。"""
    primary = MagicMock()
    primary.backend_type = "chromadb"
    primary.search.return_value = primary_results
    store = UnifiedStore(tmp_path / "rrf.db", search_backend=primary)
    fts = MagicMock()
    fts.search.return_value = fts_results
    store._fts5_fallback = fts
    db = MagicMock()
    db.get_memory.side_effect = lambda mid: _make_mem(mid)
    store.db = db
    return store


class TestRRFFusion:
    def test_rrf_ordering_beats_scale_bias(self, tmp_path):
        """两路量纲不可比时按 RRF 名次融合，而非直接拼分数。

        向量通道: a(r1) b(r2) c(r3)；FTS5 通道: b(r1) c(r2)。
        RRF: b=1/62+1/61 > c=1/63+1/62 > a=1/61（双通道共识压过单通道第一名）。
        """
        store = _make_store(
            tmp_path,
            [("a", 0.40), ("b", 0.38), ("c", 0.36)],
            [("b", 0.99), ("c", 0.02)],
        )
        results = store.search_semantic_scored("q", limit=3)
        assert [m.id for m, _ in results] == ["b", "c", "a"]

    def test_returned_score_keeps_raw_semantics(self, tmp_path):
        """返回分取命中通道最高原始分（非 RRF 值），保持 [0,1] 语义。"""
        store = _make_store(
            tmp_path,
            [("a", 0.40), ("b", 0.38), ("c", 0.36)],
            [("b", 0.99), ("c", 0.02)],
        )
        results = {m.id: s for m, s in store.search_semantic_scored("q", limit=3)}
        assert results["b"] == pytest.approx(0.99)  # max(0.38, 0.99)
        assert results["c"] == pytest.approx(0.36)  # max(0.36, 0.02)
        assert results["a"] == pytest.approx(0.40)

    def test_single_channel_degrades_to_rank_order(self, tmp_path):
        """FTS5 通道缺失/抛错时退化为单通道名次序（RRF 退化为 1/(k+rank)）。"""
        store = _make_store(tmp_path, [("a", 0.9), ("b", 0.1)], [])
        store._fts5_fallback = None
        results = store.search_semantic_scored("q", limit=2)
        assert [m.id for m, _ in results] == ["a", "b"]


# ============================================================================
# Cross-encoder 精排（RetrievalEngine._rerank + GatewayReranker）
# ============================================================================


def _candidate(mid: str, relevance: float, content: str | None = None) -> RetrievalCandidate:
    return RetrievalCandidate(
        memory_id=mid,
        content=content or f"记忆 {mid}",
        source_type="semantic:user",
        relevance=relevance,
        recency_score=0.5,
        importance_score=0.5,
        access_frequency_score=0.5,
    )


class TestModelRerank:
    def _engine(self, reranker=None) -> RetrievalEngine:
        return RetrievalEngine(store=MagicMock(), brain=None, reranker=reranker)

    def test_rerank_overwrites_relevance(self):
        """精排成功时 relevance 被统一分数覆写，排序与阈值过滤随之变化。"""
        reranker = MagicMock()
        # 原始 relevance: b 最高(0.90)；重排把 a 判为最相关(0.95)、b 判为无关(0.10)
        reranker.rerank.return_value = [0.95, 0.10, 0.30, 0.20, 0.25]
        engine = self._engine(reranker)
        candidates = [
            _candidate("a", 0.30),
            _candidate("b", 0.90),
            _candidate("c", 0.40),
            _candidate("d", 0.35),
            _candidate("e", 0.38),
        ]
        ranked = engine._rerank(candidates, "query")
        assert ranked[0].memory_id == "a"
        assert ranked[0].relevance == pytest.approx(0.95)
        # 其余名次按公式重算：c(0.42) e(0.40) d(0.38)
        assert [c.memory_id for c in ranked[1:]] == ["c", "e", "d"]
        # b 的综合分 0.10×0.4+0.5×0.6=0.34 低于 MIN_RERANK_SCORE，被阈值过滤
        assert "b" not in [c.memory_id for c in ranked]

    def test_fail_open_keeps_formula(self):
        """重排失败（返回 None）时公式路径完全不变。"""
        reranker = MagicMock()
        reranker.rerank.return_value = None
        engine = self._engine(reranker)
        candidates = [_candidate("a", 0.90), _candidate("b", 0.40), _candidate("c", 0.85), _candidate("d", 0.60)]
        ranked = engine._rerank(candidates, "query")
        assert ranked[0].memory_id == "a"
        # relevance 保持原值（0.90 × 0.4 + 0.5 × 0.6 = 0.66）
        assert ranked[0].score == pytest.approx(0.90 * 0.4 + 0.5 * 0.6)

    def test_not_triggered_below_min_candidates(self):
        """候选数低于阈值不触发重排（纯公式）。"""
        reranker = MagicMock()
        engine = self._engine(reranker)
        engine._rerank([_candidate("a", 0.9), _candidate("b", 0.8), _candidate("c", 0.7)], "q")
        reranker.rerank.assert_not_called()

    def test_top_n_cap(self):
        """候选超过上限时只送前 RERANK_TOP_N 条给重排模型。"""
        reranker = MagicMock()
        reranker.rerank.return_value = [0.5] * RetrievalEngine.RERANK_TOP_N
        engine = self._engine(reranker)
        candidates = [_candidate(f"m{i}", 0.5) for i in range(25)]
        engine._rerank(candidates, "q")
        assert len(reranker.rerank.call_args[0][1]) == RetrievalEngine.RERANK_TOP_N


class _FakeResp:
    def __init__(self, status_code=200, payload=None):
        self.status_code = status_code
        self._payload = payload or {}

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")

    def json(self):
        return self._payload


class TestGatewayReranker:
    def test_derive_host_strips_suffixes(self):
        assert (
            GatewayReranker._derive_host(
                "https://x.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
            )
            == "https://x.cn-beijing.maas.aliyuncs.com"
        )
        assert GatewayReranker._derive_host("https://api.example.com/v1") == "https://api.example.com"
        assert GatewayReranker._derive_host("https://api.example.com") == "https://api.example.com"

    def test_rerank_parses_and_clamps(self):
        r = GatewayReranker("https://x.com/compatible-mode/v1", "k", "m")
        http = MagicMock()
        http.post.return_value = _FakeResp(
            payload={"output": {"results": [{"index": 1, "relevance_score": 1.5}, {"index": 0, "relevance_score": 0.2}]}}
        )
        r._httpx = http
        scores = r.rerank("q", ["d0", "d1"])
        assert scores == pytest.approx([0.2, 1.0])  # 1.5 clamp 到 1.0

    def test_404_disables_and_short_circuits(self):
        r = GatewayReranker("https://x.com/compatible-mode/v1", "k", "m")
        http = MagicMock()
        http.post.return_value = _FakeResp(status_code=404)
        r._httpx = http
        assert r.rerank("q", ["d0"]) is None
        assert r.rerank("q", ["d0"]) is None
        assert http.post.call_count == 1  # 第二次不再发请求

    def test_exception_returns_none(self):
        r = GatewayReranker("https://x.com/v1", "k", "m")
        http = MagicMock()
        http.post.side_effect = TimeoutError
        r._httpx = http
        assert r.rerank("q", ["d0"]) is None


# ============================================================================
# Embedding 客户端与向量同步透传
# ============================================================================


class TestEmbeddingAPIClient:
    def _client(self, http) -> EmbeddingAPIClient:
        c = EmbeddingAPIClient(provider="openai", api_key="k", model="m", base_url="https://x.com/v1")
        c._httpx = http
        return c

    @staticmethod
    def _ok_response(n):
        return _FakeResp(payload={"data": [{"embedding": [0.1, 0.2]} for _ in range(n)]})

    def test_batch_sharding(self):
        http = MagicMock()
        http.post.side_effect = [self._ok_response(16), self._ok_response(1)]
        c = self._client(http)
        vectors = c.embed(["t"] * 17)
        assert vectors is not None and len(vectors) == 17
        assert http.post.call_count == 2  # BATCH_SIZE=16 → 两个分片

    def test_any_shard_failure_returns_none(self):
        http = MagicMock()
        http.post.side_effect = [self._ok_response(16), TimeoutError, TimeoutError]
        c = self._client(http)
        assert c.embed(["t"] * 17) is None
        assert http.post.call_count == 3  # 第二分片失败 + 单次重试

    def test_empty_key_returns_none(self):
        c = EmbeddingAPIClient(provider="openai", api_key="", model="m")
        assert c.embed(["t"]) is None


class TestChromaDBBackendSync:
    def test_existing_ids_and_delete_not_in(self):
        vs = MagicMock()
        vs.enabled = True
        vs._collection.get.return_value = {"ids": ["m1", "m2", "stale"]}
        backend = ChromaDBBackend(vs)
        assert backend.existing_ids() == {"m1", "m2", "stale"}
        backend.delete_not_in({"m1", "m2"})
        vs._collection.delete.assert_called_once_with(ids=["stale"])

    def test_existing_ids_none_when_disabled(self):
        vs = MagicMock()
        vs.enabled = False
        assert ChromaDBBackend(vs).existing_ids() is None


class TestVectorStoreEmbedDispatch:
    def test_api_and_local_dispatch(self):
        from newsclaw.memory.vector_store import VectorStore

        # api 模式
        vs = VectorStore.__new__(VectorStore)
        vs.embedding_source = "api"
        client = MagicMock()
        client.embed.return_value = [[0.1, 0.2]]
        vs._api_client = client
        assert vs._embed(["x"]) == [[0.1, 0.2]]

        # api 失败抛异常（调用方兜底）
        client.embed.return_value = None
        with pytest.raises(RuntimeError):
            vs._embed(["x"])

        # local 模式
        vs2 = VectorStore.__new__(VectorStore)
        vs2.embedding_source = "local"
        model = MagicMock()
        model.encode.return_value.tolist.return_value = [[0.3]]
        vs2._model = model
        assert vs2._embed(["x"]) == [[0.3]]

    def test_embedding_fingerprint(self):
        from newsclaw.memory.vector_store import VectorStore

        vs = VectorStore.__new__(VectorStore)
        vs.embedding_source = "api"
        vs._api_model = "qwen3.7-text-embedding"
        assert vs._embedding_fingerprint() == "api:qwen3.7-text-embedding"
        vs2 = VectorStore.__new__(VectorStore)
        vs2.embedding_source = "local"
        vs2.model_name = "text2vec"
        assert vs2._embedding_fingerprint() == "local:text2vec"

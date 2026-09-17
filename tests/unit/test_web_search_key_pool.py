"""Tavily/Serper 多 Key 号池单元测试。

号池是纯逻辑（解析、轮询顺序、冷却与乐观重试），不触网——所有断言通过
显式传入 ``now`` 控制时间，测试对时钟不敏感。
"""

from __future__ import annotations

from newsclaw.tools.web_search.providers.tavily import KeyPool, TavilyProvider


class TestParseKeys:
    def test_single_key_backward_compatible(self):
        assert KeyPool.parse_keys("tvly-abc") == ["tvly-abc"]

    def test_multiple_keys_split_on_common_separators(self):
        raw = "k1,k2;k3\nk4 k5"
        assert KeyPool.parse_keys(raw) == ["k1", "k2", "k3", "k4", "k5"]

    def test_empty_and_blank_input(self):
        assert KeyPool.parse_keys("") == []
        assert KeyPool.parse_keys("  ,  ;  \n ") == []

    def test_duplicates_removed_order_preserved(self):
        assert KeyPool.parse_keys("b,a,b,c,a") == ["b", "a", "c"]


class TestRotation:
    def test_all_live_keys_rotate_round_robin(self):
        pool = KeyPool()
        keys = ["k1", "k2", "k3"]
        first = pool.order(keys, now=0.0)
        second = pool.order(keys, now=0.0)
        third = pool.order(keys, now=0.0)
        # 三次调用覆盖全部 Key，且顺序逐次轮转
        assert sorted(first) == keys
        assert sorted(second) == keys
        assert first != second or second != third
        assert set(first) == set(third)

    def test_single_key_returns_that_key(self):
        pool = KeyPool()
        assert pool.order(["only"], now=0.0) == ["only"]


class TestCooldown:
    def test_penalized_key_moves_to_the_back(self):
        pool = KeyPool(cooldown_seconds=100.0)
        keys = ["k1", "k2"]
        pool.penalize("k1")
        order = pool.order(keys, now=0.0)
        assert order[0] == "k2"  # 冷却中的 k1 靠后
        assert "k1" in order

    def test_cooldown_expires(self):
        pool = KeyPool(cooldown_seconds=10.0)
        pool.penalize("k1")
        # 冷却期内：k1 靠后；过期后：重新参与轮询
        assert pool.order(["k1", "k2"], now=5.0)[0] == "k2"
        assert pool.order(["k1", "k2"], now=11.0)[0] in {"k1", "k2"}

    def test_all_keys_cooling_still_tries_earliest(self):
        pool = KeyPool(cooldown_seconds=100.0)
        pool._blocked_until["k1"] = 200.0  # 较早到期
        pool._blocked_until["k2"] = 300.0
        order = pool.order(["k1", "k2"], now=150.0)
        assert order[0] == "k1"  # 乐观重试最早到期者
        assert order[1] == "k2"

    def test_cooling_snapshot_masks_keys(self):
        pool = KeyPool(cooldown_seconds=60.0)
        pool.penalize("secret-key-abcd")
        snap = pool.cooling_snapshot(["secret-key-abcd"])
        assert list(snap) == ["...abcd"]
        assert not any("secret" in name for name in snap)


class TestProviderAvailability:
    def test_available_when_any_key_present(self, monkeypatch):
        from newsclaw.config import settings

        monkeypatch.setattr(settings, "tavily_api_key", "k1,k2")
        provider = TavilyProvider()
        assert provider.is_available() is True

    def test_unavailable_when_no_keys(self, monkeypatch):
        from newsclaw.config import settings

        monkeypatch.setattr(settings, "tavily_api_key", "   ")
        provider = TavilyProvider()
        assert provider.is_available() is False

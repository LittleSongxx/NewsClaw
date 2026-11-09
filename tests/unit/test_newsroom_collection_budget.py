"""早报每日管线的采集预算：未委派先探索、委派后只允许少量补采。

预算由 TaskExecutor 显式 activate/deactivate（不依赖 ContextVar），工具执行层
按会话 id 判断是不是主 Agent：无通道前缀的主会话才计数，``:sub:`` 子 Agent 与
IM/聊天会话（带 ``:`` 前缀）一律放行。
"""

from __future__ import annotations

import pytest

from newsclaw.newsroom import collection_budget as budget

MAIN_SESSION = "20261109_215026_266b6405"
SUB_SESSION = f"{MAIN_SESSION}:sub:deadbeef"


@pytest.fixture(autouse=True)
def _isolate():
    budget.reset()
    budget.deactivate()
    yield
    budget.reset()
    budget.deactivate()


class TestInactive:
    def test_not_activated_always_allows(self):
        for _ in range(50):
            assert budget.consume("web_search", session_id=MAIN_SESSION) is None
            assert budget.consume("web_fetch", session_id=MAIN_SESSION) is None

    def test_deactivate_stops_budgeting(self):
        budget.activate("task-1")
        assert budget.consume("web_search", session_id=MAIN_SESSION) is None
        budget.deactivate("task-1")
        for _ in range(50):
            assert budget.consume("web_search", session_id=MAIN_SESSION) is None

    def test_unrelated_tools_are_ignored(self):
        budget.activate("task-1")
        assert budget.consume("read_file", session_id=MAIN_SESSION) is None
        assert budget.consume("wiki_upsert", session_id=MAIN_SESSION) is None


class TestSessionScoping:
    def test_sub_agent_sessions_are_not_budgeted(self):
        budget.activate("task-1")
        for _ in range(50):
            assert budget.consume("web_search", session_id=SUB_SESSION) is None

    def test_chat_sessions_are_not_budgeted(self):
        budget.activate("task-1")
        for _ in range(50):
            assert budget.consume("web_search", session_id="feishu:owner:chat") is None

    def test_main_agent_is_budgeted(self):
        budget.activate("task-1")
        for _ in range(budget.SEARCH_BEFORE_DELEGATE):
            assert budget.consume("web_search", session_id=MAIN_SESSION) is None
        assert budget.consume("web_search", session_id=MAIN_SESSION) is not None


class TestBeforeDelegation:
    def test_search_budget_then_deny(self):
        budget.activate("task-1")
        for _ in range(budget.SEARCH_BEFORE_DELEGATE):
            assert budget.consume("web_search", session_id=MAIN_SESSION) is None
        denied = budget.consume("web_search", session_id=MAIN_SESSION)
        assert denied and "delegate_parallel" in denied and "预算" in denied

    def test_fetch_budget_then_deny(self):
        budget.activate("task-1")
        for _ in range(budget.FETCH_BEFORE_DELEGATE):
            assert budget.consume("web_fetch", session_id=MAIN_SESSION) is None
        denied = budget.consume("web_fetch", session_id=MAIN_SESSION)
        assert denied and "delegate_parallel" in denied

    def test_search_and_fetch_counters_are_independent(self):
        budget.activate("task-1")
        for _ in range(budget.SEARCH_BEFORE_DELEGATE):
            budget.consume("web_search", session_id=MAIN_SESSION)
        assert budget.consume("web_fetch", session_id=MAIN_SESSION) is None


class TestAfterDelegation:
    def test_delegation_resets_stage_counters(self):
        budget.activate("task-1")
        for _ in range(budget.SEARCH_BEFORE_DELEGATE):
            budget.consume("web_search", session_id=MAIN_SESSION)
        assert budget.consume("delegate_parallel", session_id=MAIN_SESSION) is None
        for _ in range(budget.SEARCH_AFTER_DELEGATE):
            assert budget.consume("web_search", session_id=MAIN_SESSION) is None
        denied = budget.consume("web_search", session_id=MAIN_SESSION)
        assert denied and "委派" in denied and "delegate_parallel" in denied

    def test_fetch_budget_after_delegation_is_small(self):
        budget.activate("task-1")
        budget.consume("delegate_parallel", session_id=MAIN_SESSION)
        for _ in range(budget.FETCH_AFTER_DELEGATE):
            assert budget.consume("web_fetch", session_id=MAIN_SESSION) is None
        assert budget.consume("web_fetch", session_id=MAIN_SESSION) is not None


class TestEnvOverrides:
    def test_limits_can_be_tuned_by_env(self, monkeypatch):
        monkeypatch.setenv("NEWSCLAW_NEWSROOM_SEARCH_BUDGET_BEFORE", "1")
        budget.activate("task-1")
        assert budget.consume("web_search", session_id=MAIN_SESSION) is None
        assert budget.consume("web_search", session_id=MAIN_SESSION) is not None

    def test_malformed_env_falls_back_to_defaults(self, monkeypatch):
        monkeypatch.setenv("NEWSCLAW_NEWSROOM_SEARCH_BUDGET_BEFORE", "not-a-number")
        assert budget._limits()[0] == budget.SEARCH_BEFORE_DELEGATE


class TestLifecycle:
    def test_activate_resets_previous_state(self):
        budget.activate("task-1")
        for _ in range(budget.SEARCH_BEFORE_DELEGATE):
            budget.consume("web_search", session_id=MAIN_SESSION)
        assert budget.consume("web_search", session_id=MAIN_SESSION) is not None
        budget.activate("task-1")  # 同一任务重跑：额度重置
        assert budget.consume("web_search", session_id=MAIN_SESSION) is None

    def test_activate_switches_active_task(self):
        budget.activate("task-1")
        for _ in range(budget.SEARCH_BEFORE_DELEGATE):
            budget.consume("web_search", session_id=MAIN_SESSION)
        budget.activate("task-2")
        assert budget.consume("web_search", session_id=MAIN_SESSION) is None

"""早报策略窄口：进化载体拒绝 + 定时任务受控放行。"""

from __future__ import annotations

from pathlib import Path

import pytest

from newsclaw.config import settings
from newsclaw.core.policy_v2 import (
    ConfirmationMode,
    DecisionAction,
    PolicyContext,
    PolicyEngineV2,
    ToolCallEvent,
)
from newsclaw.newsroom.policy import (
    DAILY_TASK_ID,
    REVIEW_TASK_ID,
    is_protected_evolution_file,
    write_manifest_from_agent,
)
from newsclaw.newsroom.sources import load_sources, sources_path
from newsclaw.wiki.store import upsert_daily_section


@pytest.fixture
def isolated_newsroom(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "project_root", tmp_path)
    return tmp_path / "data" / "newsroom"


def _ctx(
    workspace: Path,
    *,
    newsroom: str = "",
    is_unattended: bool = True,
    mode: ConfirmationMode = ConfirmationMode.DEFAULT,
    task_id: str = "",
) -> PolicyContext:
    metadata = {}
    if newsroom:
        metadata["newsroom"] = newsroom
    if task_id:
        metadata["scheduled_task_id"] = task_id
    return PolicyContext(
        session_id=f"task:{task_id or 'other'}",
        workspace=workspace,
        channel="scheduler",
        is_owner=True,
        confirmation_mode=mode,
        is_unattended=is_unattended,
        unattended_strategy="deny",
        metadata=metadata,
    )


class TestNewsroomPolicyGuards:
    def test_sources_yaml_is_protected(self, isolated_newsroom):
        load_sources()
        assert is_protected_evolution_file(str(sources_path()))
        assert is_protected_evolution_file("sources.yaml")
        assert not is_protected_evolution_file("/tmp/sources.yaml")

    def test_engine_denies_direct_source_write_even_in_trust(self, isolated_newsroom, tmp_path):
        load_sources()
        engine = PolicyEngineV2()
        decision = engine.evaluate_tool_call(
            ToolCallEvent(tool="write_file", params={"path": str(sources_path()), "content": "x"}),
            _ctx(tmp_path, newsroom="daily", is_unattended=False, mode=ConfirmationMode.TRUST),
        )
        assert decision.action == DecisionAction.DENY
        assert "evolution" in decision.reason or "apply_proposal" in decision.reason

    def test_review_cannot_add_memory(self, isolated_newsroom, tmp_path):
        engine = PolicyEngineV2()
        decision = engine.evaluate_tool_call(
            ToolCallEvent(tool="add_memory", params={"content": "rule"}),
            _ctx(tmp_path, newsroom="review", task_id=REVIEW_TASK_ID),
        )
        assert decision.action == DecisionAction.DENY
        assert "memory" in decision.reason

    def test_daily_unattended_may_write_issue_artifact(self, isolated_newsroom, tmp_path):
        engine = PolicyEngineV2()
        path = isolated_newsroom / "issues" / "2026-09-18" / "daily-brief.md"
        decision = engine.evaluate_tool_call(
            ToolCallEvent(tool="write_file", params={"path": str(path), "content": "hi"}),
            _ctx(tmp_path, newsroom="daily", task_id=DAILY_TASK_ID),
        )
        assert decision.action == DecisionAction.ALLOW
        assert decision.is_unattended_path is True

    def test_daily_unattended_may_delegate_and_wiki(self, isolated_newsroom, tmp_path):
        engine = PolicyEngineV2()
        daily = _ctx(tmp_path, newsroom="daily", task_id=DAILY_TASK_ID)
        for tool in ("delegate_parallel", "wiki_upsert"):
            decision = engine.evaluate_tool_call(ToolCallEvent(tool=tool, params={}), daily)
            assert decision.action == DecisionAction.ALLOW, tool

    def test_review_unattended_may_write_proposal(self, isolated_newsroom, tmp_path):
        engine = PolicyEngineV2()
        path = isolated_newsroom / "issues" / "review-proposal.json"
        decision = engine.evaluate_tool_call(
            ToolCallEvent(tool="write_file", params={"path": str(path), "content": "{}"}),
            _ctx(tmp_path, newsroom="review", task_id=REVIEW_TASK_ID),
        )
        assert decision.action == DecisionAction.ALLOW

    def test_unattended_without_newsroom_still_denies_write(self, tmp_path):
        engine = PolicyEngineV2()
        decision = engine.evaluate_tool_call(
            ToolCallEvent(tool="write_file", params={"path": str(tmp_path / "x")}),
            _ctx(tmp_path, newsroom=""),
        )
        assert decision.action == DecisionAction.DENY
        assert decision.is_unattended_path is True

    def test_daily_cannot_write_outside_issues(self, isolated_newsroom, tmp_path):
        engine = PolicyEngineV2()
        decision = engine.evaluate_tool_call(
            ToolCallEvent(
                tool="write_file",
                params={"path": str(tmp_path / "notes.md"), "content": "x"},
            ),
            _ctx(tmp_path, newsroom="daily", task_id=DAILY_TASK_ID),
        )
        assert decision.action == DecisionAction.DENY

    def test_opt_install_may_read_newsroom_files(self, isolated_newsroom, monkeypatch):
        """ECS 装在 /opt/newsclaw 时，读早报目录不能被 /opt/** immune 拦住。"""
        monkeypatch.setattr(settings, "project_root", Path("/opt/newsclaw"))
        engine = PolicyEngineV2()
        ctx = _ctx(Path("/opt/newsclaw"), newsroom="daily", task_id=DAILY_TASK_ID)
        for tool, params in (
            ("read_file", {"path": "/opt/newsclaw/data/newsroom/sources.yaml"}),
            ("list_directory", {"path": "/opt/newsclaw/data/newsroom/issues/2026-09-18"}),
        ):
            decision = engine.evaluate_tool_call(ToolCallEvent(tool=tool, params=params), ctx)
            assert decision.safety_immune_match is None, tool
            assert decision.action == DecisionAction.ALLOW, (tool, decision.reason)

    def test_opt_install_may_write_issue_artifact(self, isolated_newsroom, monkeypatch):
        monkeypatch.setattr(settings, "project_root", Path("/opt/newsclaw"))
        engine = PolicyEngineV2()
        path = "/opt/newsclaw/data/newsroom/issues/2026-09-18/daily-brief.md"
        decision = engine.evaluate_tool_call(
            ToolCallEvent(tool="write_file", params={"path": path, "content": "hi"}),
            _ctx(Path("/opt/newsclaw"), newsroom="daily", task_id=DAILY_TASK_ID),
        )
        assert decision.safety_immune_match is None
        assert decision.action == DecisionAction.ALLOW

    def test_opt_other_package_still_immune(self, isolated_newsroom, monkeypatch):
        monkeypatch.setattr(settings, "project_root", Path("/opt/newsclaw"))
        engine = PolicyEngineV2()
        decision = engine.evaluate_tool_call(
            ToolCallEvent(tool="write_file", params={"path": "/opt/other/bin/x", "content": "x"}),
            _ctx(Path("/opt/newsclaw"), newsroom="daily", task_id=DAILY_TASK_ID),
        )
        assert decision.safety_immune_match is not None
        assert "/opt" in (decision.safety_immune_match or "")

    def test_opt_credentials_outside_newsroom_still_immune(self, isolated_newsroom, monkeypatch):
        monkeypatch.setattr(settings, "project_root", Path("/opt/newsclaw"))
        engine = PolicyEngineV2()
        decision = engine.evaluate_tool_call(
            ToolCallEvent(
                tool="write_file",
                params={"path": "/opt/newsclaw/data/llm_endpoints.json", "content": "{}"},
            ),
            _ctx(Path("/opt/newsclaw"), newsroom="daily", task_id=DAILY_TASK_ID),
        )
        assert decision.safety_immune_match is not None


class TestManifestContractWrite:
    def test_agent_cannot_self_declare_ready_without_artifacts(self, isolated_newsroom):
        with pytest.raises(ValueError, match="invalid manifest"):
            write_manifest_from_agent(
                "2026-09-18",
                '{"issue_date":"2026-09-18","title":"t","status":"ready"}',
            )

    def test_agent_rewrite_cannot_launder_rejected_issue(self, isolated_newsroom):
        """点踩降级后，同日重写 manifest 不许洗回 ready（feedback 以旧值为准）。"""
        from newsclaw.newsroom import contract
        from newsclaw.newsroom.sources import load_sources

        load_sources()
        day = contract.issue_dir("2026-09-18")
        day.mkdir(parents=True, exist_ok=True)
        for name in (
            contract.ARTIFACT_DAILY_BRIEF,
            contract.ARTIFACT_XIAOHONGSHU,
            contract.ARTIFACT_WECHAT,
        ):
            (day / name).write_text(
                "今日要点：示例标题｜说明｜https://example.com/news｜大模型\n"
                "结构校验要求去空白后足够长，并至少有一条可点开的 http 链接。\n",
                encoding="utf-8",
            )
        contract.write_manifest(
            contract.IssueManifest(
                issue_date="2026-09-18",
                title="t",
                status="rejected",
                sources_used=["AI 综合搜索"],
                items=[
                    contract.NewsItem(
                        title="示例标题",
                        url="https://example.com/news",
                        source_name="AI 综合搜索",
                    )
                ],
                feedback={"rating": -1, "comment": "太水"},
            )
        )

        # Agent 交上来的 manifest 不带 feedback（schema 里也没有这个字段）
        with pytest.raises(ValueError, match="feedback"):
            write_manifest_from_agent(
                "2026-09-18",
                '{"issue_date":"2026-09-18","title":"t","status":"ready",'
                '"sources_used":["AI 综合搜索"],'
                '"items":[{"title":"示例标题","url":"https://example.com/news",'
                '"source_name":"AI 综合搜索","one_liner":"说明"}]}',
            )

        # 落盘的旧值没有被动过：仍是 rejected + 负反馈
        manifest = contract.read_manifest("2026-09-18")
        assert manifest.status == "rejected"
        assert manifest.feedback["rating"] == -1

    def test_agent_rewrite_preserves_delivered_at(self, isolated_newsroom):
        """已出门的投递记录是系统写的字段，Agent 重写不得清掉。"""
        import json

        from newsclaw.newsroom import contract
        from newsclaw.newsroom.sources import load_sources

        load_sources()
        contract.write_manifest(
            contract.IssueManifest(
                issue_date="2026-09-18",
                title="t",
                status="partial",
                sources_used=["AI 综合搜索"],
                delivered_at="2026-09-18T08:30:00",
            )
        )
        write_manifest_from_agent(
            "2026-09-18",
            json.dumps(
                {
                    "issue_date": "2026-09-18",
                    "title": "t2",
                    "status": "partial",
                    "sources_used": ["AI 综合搜索"],
                    "delivered_at": "",
                }
            ),
        )
        assert contract.read_manifest("2026-09-18").delivered_at == "2026-09-18T08:30:00"


class TestWikiDayGuard:
    def test_invalid_day_rejected(self, isolated_newsroom, tmp_path, monkeypatch):
        monkeypatch.setattr(settings, "project_root", tmp_path)
        with pytest.raises(ValueError, match="YYYY-MM-DD"):
            upsert_daily_section("大模型", kind="topic", day="not-a-date", entries=[])

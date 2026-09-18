"""newsroom 结构化提案：解析 fail-closed、人审 apply、每日注入、API。"""

from __future__ import annotations

import json
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from newsclaw.config import settings
from newsclaw.newsroom import contract
from newsclaw.newsroom.editorial import MAX_POLICY_BULLETS, load_editorial_policy
from newsclaw.newsroom.prompts import build_daily_injection_block, build_daily_prompt
from newsclaw.newsroom.proposal import (
    MEMORY_OP_ID,
    STATUS_APPLIED,
    STATUS_INVALID,
    STATUS_NONE,
    STATUS_PENDING,
    STATUS_REJECTED,
    ProposalError,
    apply_proposal,
    audit_path,
    load_latest_proposal,
    proposal_json_path,
    reject_proposal,
)
from newsclaw.newsroom.sources import load_sources, sources_path


@pytest.fixture
def isolated_newsroom(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "project_root", tmp_path)
    return tmp_path / "data" / "newsroom"


def _evidence() -> dict:
    return {"issue_date": "2026-09-10", "dimension": "source_hit", "note": "命中差"}


def _write_proposal(payload: dict) -> None:
    path = proposal_json_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


def _add_source_payload(name: str = "arXiv 日更", op_id: str = "src-add-arxiv") -> dict:
    return {
        "version": 1,
        "created_at": "2026-09-14T20:00:00",
        "sources": [
            {
                "id": op_id,
                "action": "add",
                "name": name,
                "kind": "search",
                "query": "site:arxiv.org cs.AI",
                "weight": 3,
                "topics": ["研究与论文"],
                "evidence": _evidence(),
            }
        ],
        "policy_bullets": [],
    }


class TestProposalParse:
    def test_missing_file_is_none_not_pending(self, isolated_newsroom):
        snap = load_latest_proposal()
        assert snap.status == STATUS_NONE
        assert snap.proposal is None
        assert snap.parse_error is None

    def test_bad_json_is_invalid_not_pending(self, isolated_newsroom):
        path = proposal_json_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("{not json", encoding="utf-8")
        names_before = {s.name for s in load_sources().sources}
        snap = load_latest_proposal()
        assert snap.status == STATUS_INVALID
        assert snap.parse_error
        assert "invalid JSON" in snap.parse_error
        assert snap.proposal is None
        assert {s.name for s in load_sources().sources} == names_before

    def test_rewrite_policy_root_key_is_invalid(self, isolated_newsroom):
        _write_proposal({"policy_text": "# rewrite everything", "sources": []})
        snap = load_latest_proposal()
        assert snap.status == STATUS_INVALID
        assert "rewrite" in (snap.parse_error or "")

    def test_rewrite_action_is_invalid(self, isolated_newsroom):
        _write_proposal(
            {
                "policy_bullets": [
                    {
                        "id": "all",
                        "action": "rewrite",
                        "text": "整文件",
                        "evidence": _evidence(),
                    }
                ]
            }
        )
        snap = load_latest_proposal()
        assert snap.status == STATUS_INVALID
        assert "rewrite" in (snap.parse_error or "")

    def test_evidence_bad_dimension_is_invalid(self, isolated_newsroom):
        payload = _add_source_payload()
        payload["sources"][0]["evidence"] = {
            "issue_date": "2026-09-10",
            "dimension": "vibes",
            "note": "编造维度",
        }
        _write_proposal(payload)
        snap = load_latest_proposal()
        assert snap.status == STATUS_INVALID
        assert "dimension" in (snap.parse_error or "")
        assert "vibes" in (snap.parse_error or "")

    def test_evidence_bad_issue_date_is_invalid(self, isolated_newsroom):
        payload = _add_source_payload()
        payload["sources"][0]["evidence"] = {
            "issue_date": "上周三",
            "dimension": "source_hit",
            "note": "日期格式错",
        }
        _write_proposal(payload)
        snap = load_latest_proposal()
        assert snap.status == STATUS_INVALID
        assert "issue_date" in (snap.parse_error or "")


class TestApplyProposal:
    def test_without_apply_source_names_unchanged(self, isolated_newsroom):
        book = load_sources()
        before = {s.name for s in book.sources}
        _write_proposal(_add_source_payload())
        snap = load_latest_proposal()
        assert snap.status == STATUS_PENDING
        assert {s.name for s in load_sources().sources} == before

    def test_approved_add_updates_sources_and_daily_injection(self, isolated_newsroom):
        load_sources()
        _write_proposal(_add_source_payload("arXiv 日更"))
        result = apply_proposal(selected_op_ids=["src-add-arxiv"], actor="test")
        assert "src-add-arxiv" in result.applied_op_ids
        names = {s.name for s in load_sources().sources}
        assert "arXiv 日更" in names
        injection = build_daily_injection_block()
        daily = build_daily_prompt()
        assert "arXiv 日更" in injection
        assert "arXiv 日更" in daily
        assert load_latest_proposal().status == STATUS_APPLIED

    def test_bad_source_ops_fail_closed_yaml_bytes_unchanged(self, isolated_newsroom):
        load_sources()
        yaml_before = sources_path().read_bytes()
        cases = [
            {
                "id": "bad-weight",
                "action": "add",
                "name": "坏权重",
                "kind": "search",
                "query": "q",
                "weight": 9,
                "evidence": _evidence(),
            },
            {
                "id": "bad-kind",
                "action": "add",
                "name": "坏类型",
                "kind": "rss",
                "query": "q",
                "weight": 3,
                "evidence": _evidence(),
            },
            {
                "id": "no-query",
                "action": "add",
                "name": "无 query",
                "kind": "search",
                "query": " ",
                "weight": 3,
                "evidence": _evidence(),
            },
            {
                "id": "dup-name",
                "action": "add",
                "name": "AI 综合搜索",
                "kind": "search",
                "query": "another",
                "weight": 2,
                "evidence": _evidence(),
            },
        ]
        for item in cases:
            _write_proposal({"sources": [item]})
            with pytest.raises(ProposalError):
                apply_proposal(selected_op_ids=[item["id"]], actor="test")
            assert sources_path().read_bytes() == yaml_before
            assert load_latest_proposal().status == STATUS_PENDING

    def test_old_issue_keeps_old_names_new_issue_rejects_unknown(self, isolated_newsroom):
        load_sources()
        text = (
            "今日要点：示例标题｜一句话说明｜https://example.com/news｜大模型\n"
            "补充：结构校验要求去空白后足够长，并至少有一条可点开的 http 链接。\n"
            "无结果时必须写明无结果，不能交空壳。\n"
        )
        old_dir = contract.issue_dir("2026-09-01")
        old_dir.mkdir(parents=True, exist_ok=True)
        for name in (
            contract.ARTIFACT_DAILY_BRIEF,
            contract.ARTIFACT_XIAOHONGSHU,
            contract.ARTIFACT_WECHAT,
        ):
            (old_dir / name).write_text(text, encoding="utf-8")
        sample = [
            contract.NewsItem(
                title="示例标题",
                url="https://example.com/news",
                source_name="AI 综合搜索",
            )
        ]
        old = contract.IssueManifest(
            issue_date="2026-09-01",
            title="old",
            status="ready",
            sources_used=["AI 综合搜索"],
            items=sample,
        )
        assert old.validate() == []

        _write_proposal(_add_source_payload("新源X"))
        apply_proposal(selected_op_ids=["src-add-arxiv"], actor="test")
        assert "新源X" in {s.name for s in load_sources().sources}
        assert old.validate() == []

        new_dir = contract.issue_dir("2026-09-16")
        new_dir.mkdir(parents=True, exist_ok=True)
        for name in (
            contract.ARTIFACT_DAILY_BRIEF,
            contract.ARTIFACT_XIAOHONGSHU,
            contract.ARTIFACT_WECHAT,
        ):
            (new_dir / name).write_text(text, encoding="utf-8")
        ghost = contract.IssueManifest(
            issue_date="2026-09-16",
            title="new",
            status="ready",
            sources_used=["清单外幽灵信源"],
        )
        errors = ghost.validate()
        assert any("sources_used unknown" in e for e in errors)
        ok = contract.IssueManifest(
            issue_date="2026-09-16",
            title="new-ok",
            status="ready",
            sources_used=["新源X"],
            items=[
                contract.NewsItem(
                    title="示例标题",
                    url="https://example.com/news",
                    source_name="新源X",
                )
            ],
        )
        assert ok.validate() == []

    def test_reject_leaves_files_and_sets_rejected(self, isolated_newsroom):
        load_sources()
        yaml_before = sources_path().read_bytes()
        _write_proposal(_add_source_payload())
        policy_path = isolated_newsroom / "editorial-policy.md"
        policy_existed = policy_path.is_file()
        policy_before = policy_path.read_bytes() if policy_existed else None
        snap = reject_proposal(actor="test", reason="不采纳")
        assert snap.status == STATUS_REJECTED
        assert sources_path().read_bytes() == yaml_before
        if policy_existed:
            assert policy_path.read_bytes() == policy_before
        else:
            assert not policy_path.is_file()
        lines = audit_path().read_text(encoding="utf-8").strip().splitlines()
        assert lines
        record = json.loads(lines[-1])
        assert record["action"] == "reject"
        assert record["ok"] is True

    def test_policy_over_cap_and_rewrite_rejected(self, isolated_newsroom):
        load_sources()
        yaml_before = sources_path().read_bytes()
        too_many = [
            {
                "id": f"b{i}",
                "action": "add",
                "text": f"规则 {i}",
                "evidence": _evidence(),
            }
            for i in range(MAX_POLICY_BULLETS + 1)
        ]
        _write_proposal({"policy_bullets": too_many})
        snap = load_latest_proposal()
        assert snap.status == STATUS_INVALID
        assert "exceed cap" in (snap.parse_error or "")

        _write_proposal(
            {
                "policy_bullets": [
                    {
                        "id": "x",
                        "action": "replace_all",
                        "text": "整文件",
                        "evidence": _evidence(),
                    }
                ]
            }
        )
        snap = load_latest_proposal()
        assert snap.status == STATUS_INVALID
        with pytest.raises(ProposalError):
            apply_proposal(selected_op_ids=["x"], actor="test")
        assert sources_path().read_bytes() == yaml_before

    def test_unselected_source_op_does_not_write_yaml(self, isolated_newsroom):
        load_sources()
        before = {s.name for s in load_sources().sources}
        _write_proposal(
            {
                "sources": [
                    {
                        "id": "src-add-arxiv",
                        "action": "add",
                        "name": "不该出现",
                        "kind": "search",
                        "query": "q",
                        "weight": 3,
                        "evidence": _evidence(),
                    }
                ],
                "policy_bullets": [
                    {
                        "id": "prefer-funding",
                        "action": "add",
                        "text": "融资只留 B 轮以上",
                        "evidence": _evidence(),
                    }
                ],
            }
        )
        apply_proposal(selected_op_ids=["prefer-funding"], actor="test")
        assert {s.name for s in load_sources().sources} == before
        policy = load_editorial_policy()
        assert any(b.id == "prefer-funding" for b in policy.bullets)

    def test_memory_only_when_checked(self, isolated_newsroom):
        load_sources()
        written: list[tuple[str, dict]] = []

        def writer(text: str, metadata: dict) -> str:
            written.append((text, metadata))
            return "mem-1"

        _write_proposal(
            {
                "sources": [
                    {
                        "id": "src-add-arxiv",
                        "action": "add",
                        "name": "记忆旁路源",
                        "kind": "search",
                        "query": "q",
                        "weight": 3,
                        "evidence": _evidence(),
                    }
                ],
                "memory_rule": {
                    "text": "融资新闻只保留 B 轮以上",
                    "evidence": _evidence(),
                },
            }
        )
        apply_proposal(
            selected_op_ids=["src-add-arxiv"],
            apply_memory=False,
            actor="test",
            memory_writer=writer,
        )
        assert written == []
        assert "记忆旁路源" in {s.name for s in load_sources().sources}

        _write_proposal(
            {
                "sources": [],
                "memory_rule": {
                    "text": "融资新闻只保留 B 轮以上",
                    "evidence": _evidence(),
                },
            }
        )
        result = apply_proposal(
            selected_op_ids=[MEMORY_OP_ID],
            apply_memory=True,
            actor="test",
            memory_writer=writer,
        )
        assert result.memory_written is True
        assert written[0][0] == "融资新闻只保留 B 轮以上"
        assert written[0][1]["source"] == "newsroom_review"

    def test_partial_apply_keeps_unselected_pending(self, isolated_newsroom):
        load_sources()
        _write_proposal(
            {
                "sources": [
                    {
                        "id": "src-a",
                        "action": "add",
                        "name": "源 A",
                        "kind": "search",
                        "query": "a",
                        "weight": 3,
                        "evidence": _evidence(),
                    },
                    {
                        "id": "src-b",
                        "action": "add",
                        "name": "源 B",
                        "kind": "search",
                        "query": "b",
                        "weight": 3,
                        "evidence": _evidence(),
                    },
                ],
                "policy_bullets": [
                    {
                        "id": "prefer-short",
                        "action": "add",
                        "text": "标题更短",
                        "evidence": _evidence(),
                    }
                ],
            }
        )
        result = apply_proposal(selected_op_ids=["prefer-short"], actor="test")
        snap = load_latest_proposal()
        assert snap.status == STATUS_PENDING
        assert result.applied_op_ids == ["prefer-short"]
        assert "src-a" in snap.remaining_op_ids()
        assert "源 A" not in {s.name for s in load_sources().sources}
        assert any(b.id == "prefer-short" for b in load_editorial_policy().bullets)

        apply_proposal(selected_op_ids=["src-a"], actor="test")
        snap = load_latest_proposal()
        assert snap.status == STATUS_PENDING
        assert "src-a" in snap.applied_op_ids
        assert "src-b" in snap.remaining_op_ids()
        assert "源 A" in {s.name for s in load_sources().sources}
        assert "源 B" not in {s.name for s in load_sources().sources}

    def test_second_apply_rejected(self, isolated_newsroom):
        load_sources()
        _write_proposal(_add_source_payload())
        apply_proposal(selected_op_ids=["src-add-arxiv"], actor="test")
        yaml_after = sources_path().read_bytes()
        with pytest.raises(ProposalError, match="already applied"):
            apply_proposal(selected_op_ids=["src-add-arxiv"], actor="test")
        assert sources_path().read_bytes() == yaml_after

    def test_policy_cap_error_explains_current_and_added(self, isolated_newsroom):
        """方针已满时 add 到 apply 才炸——文案要讲清现状与出路。"""
        from newsclaw.newsroom.editorial import save_editorial_policy
        from newsclaw.newsroom.editorial import EditorialPolicy, PolicyBullet

        load_sources()
        save_editorial_policy(
            EditorialPolicy(
                bullets=[
                    PolicyBullet(id=f"p{i}", text=f"既有规则 {i}")
                    for i in range(MAX_POLICY_BULLETS)
                ]
            )
        )
        _write_proposal(
            {
                "policy_bullets": [
                    {
                        "id": "new-one",
                        "action": "add",
                        "text": "新规则",
                        "evidence": _evidence(),
                    }
                ]
            }
        )
        with pytest.raises(ProposalError) as excinfo:
            apply_proposal(selected_op_ids=["new-one"], actor="test")
        message = str(excinfo.value)
        assert f"cap {MAX_POLICY_BULLETS}" in message
        assert f"currently {MAX_POLICY_BULLETS}" in message
        assert "remove some bullets" in message
        assert len(load_editorial_policy().bullets) == MAX_POLICY_BULLETS

    def test_memory_failure_does_not_rollback_yaml(self, isolated_newsroom):
        load_sources()

        def boom(_text: str, _meta: dict) -> str:
            raise RuntimeError("memory down")

        _write_proposal(
            {
                **_add_source_payload(),
                "memory_rule": {"text": "一条规则", "evidence": _evidence()},
            }
        )
        result = apply_proposal(
            selected_op_ids=["src-add-arxiv", MEMORY_OP_ID],
            apply_memory=True,
            actor="test",
            memory_writer=boom,
        )
        assert "arXiv 日更" in {s.name for s in load_sources().sources}
        assert result.memory_written is False
        assert result.memory_error
        snap = load_latest_proposal()
        assert snap.status == STATUS_PENDING
        assert "src-add-arxiv" in snap.applied_op_ids
        assert MEMORY_OP_ID not in snap.applied_op_ids
        assert MEMORY_OP_ID in snap.remaining_op_ids()


class TestDailyInjectionAtTrigger:
    def test_executor_rebuilds_injection_from_disk(self, isolated_newsroom):
        from newsclaw.scheduler.executor import TaskExecutor

        load_sources()
        stale = build_daily_prompt()
        _write_proposal(_add_source_payload("运行时新源"))
        apply_proposal(selected_op_ids=["src-add-arxiv"], actor="test")
        task = SimpleNamespace(
            prompt=stale,
            name="AI 早报",
            description="daily",
            metadata={"newsroom": "daily"},
            channel_id="",
            chat_id="",
            script_path="",
            skill_ids=[],
        )
        text = TaskExecutor()._build_prompt(task)
        assert "运行时新源" in text
        assert text.count("当期强制上下文 · 代码注入 · 开始") == 1

    def test_injection_failure_aborts(self, isolated_newsroom, monkeypatch):
        from newsclaw.newsroom import prompts
        from newsclaw.scheduler.executor import TaskExecutor

        def boom(_prompt: str) -> str:
            raise RuntimeError("disk gone")

        monkeypatch.setattr(prompts, "with_runtime_injection", boom)
        task = SimpleNamespace(
            prompt="cached",
            name="AI 早报",
            description="daily",
            metadata={"newsroom": "daily"},
            channel_id="",
            chat_id="",
            script_path="",
            skill_ids=[],
        )
        with pytest.raises(RuntimeError, match="过期信源"):
            TaskExecutor()._build_prompt(task)

    def test_archive_pending_proposal(self, isolated_newsroom):
        from newsclaw.newsroom.proposal import archive_pending_proposal_if_needed

        _write_proposal(_add_source_payload("待审源"))
        dest = archive_pending_proposal_if_needed()
        assert dest is not None
        assert dest.is_file()
        assert "待审源" in dest.read_text(encoding="utf-8")


class TestProposalAPI:
    @pytest.fixture
    def client(self, isolated_newsroom):
        from newsclaw.api.routes.newsroom import router

        app = FastAPI()
        app.include_router(router)
        load_sources()
        return TestClient(app)

    def test_get_apply_reject_roundtrip(self, client):
        empty = client.get("/api/newsroom/proposal")
        assert empty.status_code == 200
        assert empty.json()["status"] == STATUS_NONE

        _write_proposal(_add_source_payload("API 新源"))
        pending = client.get("/api/newsroom/proposal")
        assert pending.json()["status"] == STATUS_PENDING
        assert pending.json()["ops"][0]["id"] == "src-add-arxiv"

        skipped = client.post(
            "/api/newsroom/proposal/apply",
            json={"op_ids": [], "apply_memory": False},
        )
        assert skipped.status_code == 422
        assert "API 新源" not in {s.name for s in load_sources().sources}

        applied = client.post(
            "/api/newsroom/proposal/apply",
            json={"op_ids": ["src-add-arxiv"], "actor": "test"},
        )
        assert applied.status_code == 200
        assert applied.json()["status"] == STATUS_APPLIED
        assert "API 新源" in {s.name for s in load_sources().sources}

        again = client.post(
            "/api/newsroom/proposal/apply",
            json={"op_ids": ["src-add-arxiv"]},
        )
        assert again.status_code == 422

        _write_proposal(_add_source_payload("拒绝源", op_id="src-reject"))
        rejected = client.post(
            "/api/newsroom/proposal/reject",
            json={"actor": "test", "reason": "no"},
        )
        assert rejected.status_code == 200
        assert rejected.json()["status"] == STATUS_REJECTED
        assert "拒绝源" not in {s.name for s in load_sources().sources}

    def test_apply_invalid_source_returns_422(self, client):
        yaml_before = sources_path().read_bytes()
        _write_proposal(
            {
                "sources": [
                    {
                        "id": "bad",
                        "action": "add",
                        "name": "坏",
                        "kind": "rss",
                        "query": "q",
                        "weight": 3,
                        "evidence": _evidence(),
                    }
                ]
            }
        )
        res = client.post("/api/newsroom/proposal/apply", json={"op_ids": ["bad"]})
        assert res.status_code == 422
        assert sources_path().read_bytes() == yaml_before

    def test_get_issue_exposes_manifest_error(self, client, isolated_newsroom):
        day = isolated_newsroom / "issues" / "2026-09-10"
        day.mkdir(parents=True)
        (day / "manifest.json").write_text("{not json", encoding="utf-8")
        res = client.get("/api/newsroom/issues/2026-09-10")
        assert res.status_code == 200
        body = res.json()
        assert body["manifest"] is None
        assert "corrupt" in (body.get("manifest_error") or "")

    def test_editorial_policy_readonly(self, client):
        res = client.get("/api/newsroom/editorial-policy")
        assert res.status_code == 200
        body = res.json()
        assert "text" in body
        assert body["max_bullets"] == MAX_POLICY_BULLETS
        assert "bullets" in body

    def test_feedback_roundtrip_with_items(self, client, isolated_newsroom):
        from newsclaw.newsroom import contract as newsroom_contract

        load_sources()
        newsroom_contract.write_manifest(
            newsroom_contract.IssueManifest(
                issue_date="2026-09-16",
                title="t",
                status="partial",
                items=[
                    newsroom_contract.NewsItem(
                        title="标题", url="https://example.com/a", source_name=""
                    )
                ],
            )
        )
        res = client.post(
            "/api/newsroom/feedback",
            json={
                "issue_date": "2026-09-16",
                "rating": 0,
                "comment": "",
                "items": [
                    {"url": "https://example.com/a", "rating": -1, "comment": "这条水"}
                ],
            },
        )
        assert res.status_code == 200
        body = res.json()
        assert body["items"][0]["url"] == "https://example.com/a"

        listed = client.get("/api/newsroom/feedback").json()
        assert listed["summary"]["item_down"] == 1
        assert listed["item_records"][0]["item_url"] == "https://example.com/a"
        # 条目反馈不改变期次状态
        manifest = newsroom_contract.read_manifest("2026-09-16")
        assert manifest.status == "partial"

    def test_feedback_rejects_unknown_item_url(self, client, isolated_newsroom):
        from newsclaw.newsroom import contract as newsroom_contract

        load_sources()
        newsroom_contract.write_manifest(
            newsroom_contract.IssueManifest(issue_date="2026-09-16", title="t")
        )
        res = client.post(
            "/api/newsroom/feedback",
            json={
                "issue_date": "2026-09-16",
                "rating": 1,
                "items": [{"url": "https://not-in-ledger.example/x", "rating": -1}],
            },
        )
        assert res.status_code == 422
        assert "not in manifest.items" in res.json()["error"]

    def test_status_endpoint_aggregates_mainline(self, client, isolated_newsroom):
        from newsclaw.newsroom import contract as newsroom_contract

        load_sources()
        newsroom_contract.write_manifest(
            newsroom_contract.IssueManifest(issue_date="2026-09-16", title="t")
        )
        res = client.get("/api/newsroom/status")
        assert res.status_code == 200
        body = res.json()
        assert body["config"]["enabled"] is True
        # 测试环境没有活动调度器：任务分区应优雅报缺失而不是报错
        assert body["tasks"]["daily"]["present"] is False
        assert body["tasks"]["review"]["present"] is False
        assert body["last_issue"]["date"] == "2026-09-16"
        assert body["last_issue"]["status"] == "partial"
        assert body["proposal"]["status"] == STATUS_NONE
        assert body["feedback"]["down"] == 0
        assert isinstance(body["dedup_pool"]["seen_urls"], int)

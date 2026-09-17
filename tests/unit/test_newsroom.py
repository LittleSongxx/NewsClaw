"""newsroom 主线单元测试。

覆盖四个契约面：期次目录契约（contract）、信源清单（sources）、运行配置
（config）、反馈存储（feedback），以及 seed 的幂等播种与对账语义。

路径隔离：所有测试通过 monkeypatch settings.project_root 指向 tmp_path，
newsroom 各模块的路径均由 contract.newsroom_root() 动态解析，无需逐模块打补丁。
"""

from __future__ import annotations

import json

import pytest

from newsclaw.config import settings
from newsclaw.newsroom import contract, feedback
from newsclaw.newsroom.config import NewsroomConfig, load_config, save_config
from newsclaw.newsroom.seed import DAILY_TASK_ID, REVIEW_TASK_ID, ensure_newsroom_tasks
from newsclaw.newsroom.sources import NewsSource, SourceBook, load_sources, save_sources


@pytest.fixture(autouse=True)
def isolated_newsroom(tmp_path, monkeypatch):
    """把 data/newsroom 重定向到 tmp_path，测试之间互不污染。"""
    monkeypatch.setattr(settings, "project_root", tmp_path)
    return tmp_path / "data" / "newsroom"


# ── contract ────────────────────────────────────────────────────────


_VALID_ARTIFACT = (
    "今日要点：示例标题｜一句话说明｜https://example.com/news｜大模型\n"
    "补充：结构校验要求去空白后足够长，并至少有一条可点开的 http 链接。\n"
    "无结果时必须写明无结果，不能交空壳。\n"
)


def _sample_items() -> list[contract.NewsItem]:
    return [
        contract.NewsItem(
            title="示例标题",
            url="https://example.com/news",
            source_name="AI 综合搜索",
            one_liner="一句话说明",
        )
    ]


def _write_required_artifacts(issue_date: str, text: str = _VALID_ARTIFACT) -> None:
    day = contract.issue_dir(issue_date)
    day.mkdir(parents=True, exist_ok=True)
    for name in (
        contract.ARTIFACT_DAILY_BRIEF,
        contract.ARTIFACT_XIAOHONGSHU,
        contract.ARTIFACT_WECHAT,
    ):
        (day / name).write_text(text, encoding="utf-8")


class TestContract:
    def test_manifest_roundtrip(self):
        _write_required_artifacts("2026-09-16")
        manifest = contract.IssueManifest(
            issue_date="2026-09-16",
            title="AI 早报 #1",
            status="ready",
            sources_used=["AI 综合搜索"],
            items=_sample_items(),
            wiki_entries=["AI早报/大模型.md"],
        )
        manifest.scores = {
            dim: contract.ScoreEntry(score=4, rationale="ok") for dim in contract.SCORE_DIMENSIONS
        }
        path = contract.write_manifest(manifest)
        assert path.name == contract.MANIFEST_FILENAME

        loaded = contract.read_manifest("2026-09-16")
        assert loaded is not None
        assert loaded.title == "AI 早报 #1"
        assert loaded.scores["dedup"].score == 4
        assert loaded.scores["dedup"].rationale == "ok"

    def test_manifest_validation_rejects_bad_date_and_status(self):
        assert contract.IssueManifest(issue_date="09/16").validate()
        assert contract.IssueManifest(issue_date="2026-09-16", status="garbage").validate()
        bad_score = contract.IssueManifest(
            issue_date="2026-09-16",
            scores={"source_hit": contract.ScoreEntry(score=9)},
        )
        assert bad_score.validate()

    def test_budget_exceeded_demotes_ready_and_lists_artifacts(self):
        load_sources()
        _write_required_artifacts("2026-09-16")
        contract.write_manifest(
            contract.IssueManifest(
                issue_date="2026-09-16",
                title="t",
                status="ready",
                sources_used=["AI 综合搜索"],
                items=_sample_items(),
            )
        )
        snapshot = contract.describe_issue_artifacts("2026-09-16")
        assert snapshot["present"] == [
            contract.ARTIFACT_DAILY_BRIEF,
            contract.ARTIFACT_XIAOHONGSHU,
            contract.ARTIFACT_WECHAT,
        ]
        assert snapshot["missing"] == []
        assert snapshot["manifest_status"] == "ready"
        assert contract.demote_ready_on_budget_exceeded("2026-09-16") is True
        loaded = contract.read_manifest("2026-09-16")
        assert loaded is not None
        assert loaded.status == "partial"
        assert contract.demote_ready_on_budget_exceeded("2026-09-16") is False

    def test_write_manifest_rejects_invalid_payload(self):
        with pytest.raises(ValueError):
            contract.write_manifest(contract.IssueManifest(issue_date="not-a-date"))

    def test_ready_status_requires_three_artifacts(self, isolated_newsroom):
        manifest = contract.IssueManifest(issue_date="2026-09-15", title="t1", status="ready")
        errors = manifest.validate()
        assert any("missing artifact" in e or "sources_used" in e for e in errors)
        with pytest.raises(ValueError):
            contract.write_manifest(manifest)

    def test_ready_rejects_unknown_source(self, isolated_newsroom):
        load_sources()
        _write_required_artifacts("2026-09-15")
        manifest = contract.IssueManifest(
            issue_date="2026-09-15",
            title="t1",
            status="ready",
            sources_used=["清单外幽灵信源"],
        )
        errors = manifest.validate()
        assert any("sources_used unknown" in e for e in errors)

    def test_ready_rejects_empty_shell_artifact(self, isolated_newsroom):
        load_sources()
        _write_required_artifacts("2026-09-15", text="ok")
        manifest = contract.IssueManifest(
            issue_date="2026-09-15",
            title="t1",
            status="ready",
            sources_used=["AI 综合搜索"],
        )
        errors = manifest.validate()
        assert any("too short" in e for e in errors)

    def test_ready_accepts_valid_sources_and_structure(self, isolated_newsroom):
        load_sources()
        _write_required_artifacts("2026-09-15")
        manifest = contract.IssueManifest(
            issue_date="2026-09-15",
            title="t1",
            status="ready",
            sources_used=["AI 综合搜索"],
            items=_sample_items(),
        )
        assert manifest.validate() == []
        contract.write_manifest(manifest)

    def test_ready_ignores_scores_when_files_empty(self, isolated_newsroom):
        load_sources()
        _write_required_artifacts("2026-09-15", text="   ")
        manifest = contract.IssueManifest(
            issue_date="2026-09-15",
            title="t1",
            status="ready",
            sources_used=["AI 综合搜索"],
            scores={"source_hit": contract.ScoreEntry(score=5, rationale="perfect")},
        )
        errors = manifest.validate()
        assert any("too short" in e for e in errors)
        assert not any("score" in e and "ready" in e for e in errors)

    def test_ready_forbidden_when_feedback_rejected(self, isolated_newsroom):
        load_sources()
        _write_required_artifacts("2026-09-15")
        manifest = contract.IssueManifest(
            issue_date="2026-09-15",
            title="t1",
            status="ready",
            sources_used=["AI 综合搜索"],
            items=_sample_items(),
            feedback={"rating": -1, "comment": "太水"},
        )
        errors = manifest.validate()
        assert any("feedback is reject/low" in e for e in errors)

    def test_rejected_status_is_legal(self, isolated_newsroom):
        manifest = contract.IssueManifest(issue_date="2026-09-15", title="t1", status="rejected")
        assert manifest.validate() == []

    def test_classify_issue_failure_enum(self):
        assert (
            contract.classify_issue_failure(exit_reason="budget_exceeded")
            == contract.IssueFailureReason.BUDGET_EXCEEDED
        )
        assert (
            contract.classify_issue_failure(
                validate_errors=["status=ready but missing artifact: x"]
            )
            == contract.IssueFailureReason.ARTIFACTS_INCOMPLETE
        )
        assert (
            contract.classify_issue_failure(exit_reason="policy_denied")
            == contract.IssueFailureReason.POLICY_DENIED
        )

    def test_list_issues_skips_dirs_without_manifest(self, isolated_newsroom):
        ready = contract.IssueManifest(issue_date="2026-09-15", title="t1", status="partial")
        contract.write_manifest(ready)
        # 半成品目录：有产物无 manifest，不应出现在列表里
        (isolated_newsroom / "issues" / "2026-09-16").mkdir(parents=True)
        (isolated_newsroom / "issues" / "2026-09-16" / contract.ARTIFACT_DAILY_BRIEF).write_text(
            "x", encoding="utf-8"
        )

        issues = contract.list_issues()
        assert [i["issue_date"] for i in issues] == ["2026-09-15"]
        assert issues[0]["artifacts"]["daily-brief"] is False

    def test_list_issues_surfaces_corrupt_manifest_and_date_mismatch(self, isolated_newsroom):
        bad_dir = isolated_newsroom / "issues" / "2026-09-10"
        bad_dir.mkdir(parents=True)
        (bad_dir / contract.MANIFEST_FILENAME).write_text("{not json", encoding="utf-8")

        mismatch = contract.IssueManifest(issue_date="2026-01-01", title="wrong", status="partial")
        other = isolated_newsroom / "issues" / "2026-09-11"
        other.mkdir(parents=True)
        (other / contract.MANIFEST_FILENAME).write_text(
            __import__("json").dumps(mismatch.to_dict()),
            encoding="utf-8",
        )

        issues = {row["issue_date"]: row for row in contract.list_issues()}
        assert issues["2026-09-10"]["status"] == "invalid"
        assert "corrupt" in (issues["2026-09-10"].get("manifest_error") or "")
        assert issues["2026-09-11"]["status"] == "invalid"
        assert "directory" in (issues["2026-09-11"].get("manifest_error") or "")

    def test_read_issue_content(self, isolated_newsroom):
        day_dir = isolated_newsroom / "issues" / "2026-09-14"
        day_dir.mkdir(parents=True)
        (day_dir / contract.ARTIFACT_XIAOHONGSHU).write_text("# 笔记", encoding="utf-8")
        content = contract.read_issue_content("2026-09-14")
        assert content is not None
        assert content["xiaohongshu"] == "# 笔记"
        assert content["wechat"] is None
        assert contract.read_issue_content("2026-01-01") is None


# ── sources ─────────────────────────────────────────────────────────


class TestSources:
    def test_first_load_materializes_default_book(self, isolated_newsroom):
        book = load_sources()
        assert (isolated_newsroom / "sources.yaml").is_file()
        assert len(book.sources) == 4
        assert {s.kind for s in book.sources} == {"search"}
        assert not book.validate()

    def test_roundtrip_preserves_sources(self, isolated_newsroom):
        book = SourceBook()
        book.sources.append(
            NewsSource(
                name="某站", kind="site", url="https://example.com", weight=2, topics=["公司动态"]
            )
        )
        save_sources(book)
        reloaded = load_sources()
        assert [s.name for s in reloaded.sources][-1] == "某站"
        assert reloaded.sources[-1].kind == "site"

    def test_validation_errors(self):
        book = SourceBook(
            sources=[
                NewsSource(name="dup", kind="search", query="a"),
                NewsSource(name="dup", kind="search", query="a"),
            ]
        )
        assert any("duplicate" in e for e in book.validate())

        bad_kind = SourceBook(sources=[NewsSource(name="x", kind="rss")])
        assert any("kind" in e for e in bad_kind.validate())

        missing_query = SourceBook(sources=[NewsSource(name="x", kind="search", query=" ")])
        assert any("requires query" in e for e in missing_query.validate())

        with pytest.raises(ValueError):
            save_sources(bad_kind)


# ── config ──────────────────────────────────────────────────────────


class TestNewsroomConfig:
    def test_defaults_when_file_missing(self, isolated_newsroom):
        cfg = load_config()
        assert cfg.enabled is True
        assert cfg.daily_cron == "0 8 * * *"
        assert cfg.review_cron == "0 20 * * 0"
        assert cfg.obsidian_vault == ""

    def test_save_load_roundtrip_with_unknown_keys_as_extra(self, isolated_newsroom):
        cfg = NewsroomConfig(enabled=False, daily_cron="30 7 * * *")
        save_config(cfg)
        loaded = load_config()
        assert loaded.enabled is False
        assert loaded.daily_cron == "30 7 * * *"

        # 未知键不丢弃（向前兼容），known 键回读类型正确
        path = isolated_newsroom / "config.yaml"
        path.write_text(
            path.read_text(encoding="utf-8") + "\nfuture_key: hello\n",
            encoding="utf-8",
        )
        assert load_config().extra.get("future_key") == "hello"

    def test_from_mapping_keeps_unknown_keys_out_of_constructor(self, isolated_newsroom):
        merged = NewsroomConfig.from_mapping(
            {**NewsroomConfig(enabled=False).to_dict(), "future_key": "hello", "enabled": True}
        )
        assert merged.enabled is True
        assert merged.extra.get("future_key") == "hello"

    def test_corrupt_config_disables_pipeline(self, isolated_newsroom):
        path = isolated_newsroom / "config.yaml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("{[not valid yaml", encoding="utf-8")
        cfg = load_config()
        assert cfg.enabled is False
        assert cfg.load_error


# ── feedback ────────────────────────────────────────────────────────


class TestFeedback:
    async def test_set_and_export_and_manifest_sync(self, isolated_newsroom):
        contract.write_manifest(contract.IssueManifest(issue_date="2026-09-16", title="t"))
        record = await feedback.set_feedback("2026-09-16", 1, "选题很准")
        assert record["rating"] == 1

        # 导出快照（Agent 的消费入口）
        payload = json.loads(
            (isolated_newsroom / "feedback-export.json").read_text(encoding="utf-8")
        )
        assert payload["summary"]["up"] == 1
        assert payload["issues"]["2026-09-16"]["comment"] == "选题很准"

        # manifest 摘要已同步（列表视图免联表）
        manifest = contract.read_manifest("2026-09-16")
        assert manifest.feedback == {"rating": 1, "comment": "选题很准"}

    async def test_overwrite_semantics_and_rating_validation(self, isolated_newsroom):
        await feedback.set_feedback("2026-09-16", -1, "太水")
        records = await feedback.get_all_feedback()
        assert len(records) == 1 and records[0]["rating"] == -1

        with pytest.raises(ValueError):
            await feedback.set_feedback("2026-09-16", 2)


# ── seed ────────────────────────────────────────────────────────────


class FakeScheduler:
    """TaskScheduler 的最小替身：实现 seed 依赖的五个方法。"""

    def __init__(self):
        self.tasks: dict[str, object] = {}
        self.saved = 0

    def get_task(self, task_id):
        return self.tasks.get(task_id)

    async def add_task(self, task):
        self.tasks[task.id] = task
        return task.id

    async def update_task(self, task_id, updates):
        for key, value in updates.items():
            setattr(self.tasks[task_id], key, value)

    async def save(self):
        self.saved += 1


class TestSeed:
    async def test_first_seed_creates_both_tasks(self, isolated_newsroom):
        scheduler = FakeScheduler()
        changed = await ensure_newsroom_tasks(scheduler)
        assert changed is True
        daily = scheduler.tasks[DAILY_TASK_ID]
        review = scheduler.tasks[REVIEW_TASK_ID]

        assert daily.agent_profile_id == "ai-news-editor"
        assert daily.trigger_config["cron"] == "0 8 * * *"
        assert daily.no_schedule_tools is True
        assert daily.silent is True
        assert daily.deletable is False
        assert daily.metadata["newsroom"] == "daily"
        assert review.trigger_config["cron"] == "0 20 * * 0"
        # 首次播种同时落盘默认 config/sources，用户开箱可发现
        assert (isolated_newsroom / "config.yaml").is_file()
        assert (isolated_newsroom / "sources.yaml").is_file()

    async def test_second_seed_is_noop(self, isolated_newsroom):
        scheduler = FakeScheduler()
        await ensure_newsroom_tasks(scheduler)
        assert await ensure_newsroom_tasks(scheduler) is False
        assert scheduler.saved == 1

    async def test_config_change_reconciles_cron_and_enabled(self, isolated_newsroom):
        scheduler = FakeScheduler()
        await ensure_newsroom_tasks(scheduler)

        cfg = load_config()
        cfg.daily_cron = "30 7 * * *"
        cfg.enabled = False
        save_config(cfg)

        assert await ensure_newsroom_tasks(scheduler) is True
        daily = scheduler.tasks[DAILY_TASK_ID]
        assert daily.trigger_config["cron"] == "30 7 * * *"
        assert daily.enabled is False

    async def test_reconcile_backfills_timeout(self, isolated_newsroom):
        scheduler = FakeScheduler()
        await ensure_newsroom_tasks(scheduler)
        daily = scheduler.tasks[DAILY_TASK_ID]
        daily.metadata = {"newsroom": "daily", "prompt_version": 16}
        assert await ensure_newsroom_tasks(scheduler) is True
        assert daily.metadata["timeout_seconds"] == load_config().task_timeout_seconds

    async def test_prompt_refresh_only_on_version_bump(self, isolated_newsroom):
        from newsclaw.newsroom import prompts, seed

        scheduler = FakeScheduler()
        await ensure_newsroom_tasks(scheduler)
        original_prompt = scheduler.tasks[DAILY_TASK_ID].prompt

        # 用户在 GUI 改了 prompt：版本不变时不覆盖
        scheduler.tasks[DAILY_TASK_ID].prompt = "用户自定义 prompt"
        assert await ensure_newsroom_tasks(scheduler) is False
        assert scheduler.tasks[DAILY_TASK_ID].prompt == "用户自定义 prompt"

        # 版本递增 → prompt 刷新到新版
        monkey_target = prompts.PROMPT_VERSION
        try:
            prompts.PROMPT_VERSION = monkey_target + 1
            seed.PROMPT_VERSION = monkey_target + 1
            assert await ensure_newsroom_tasks(scheduler) is True
            assert scheduler.tasks[DAILY_TASK_ID].prompt == original_prompt
            assert scheduler.tasks[DAILY_TASK_ID].metadata["prompt_version"] == monkey_target + 1
        finally:
            prompts.PROMPT_VERSION = monkey_target
            seed.PROMPT_VERSION = monkey_target

    async def test_background_seeding_clears_flag_on_failure(self, isolated_newsroom, monkeypatch):
        from newsclaw.newsroom import seed

        seed._seeding_started = False
        scheduler = FakeScheduler()

        async def boom(_scheduler):
            raise RuntimeError("seed failed")

        monkeypatch.setattr(seed, "ensure_newsroom_tasks", boom)
        monkeypatch.setattr("newsclaw.scheduler.get_active_scheduler", lambda: scheduler)

        first = seed.start_background_seeding()
        assert first is not None
        await first
        assert seed._seeding_started is False

        second = seed.start_background_seeding()
        assert second is not None
        await second
        seed._seeding_started = False

    async def test_prompts_reference_workspace_paths(self, isolated_newsroom):
        from newsclaw.newsroom.prompts import build_daily_prompt, build_review_prompt

        daily = build_daily_prompt(load_config())
        review = build_review_prompt()
        root = str(isolated_newsroom)
        assert root in daily and "sources.yaml" in daily
        assert "manifest.json" in daily and "editorial-policy.md" in daily
        assert root in review and "feedback-export.json" in review and "reviews" in review
        assert "review-proposal.md" in review
        assert "review-proposal.json" in review
        assert "禁止直接改" in review
        assert "按上面的清单" in daily
        assert "当期强制上下文" in daily
        assert "news-collector" in daily
        assert "items" in daily
        assert "已见 URL" in daily
        # 空 vault = 跳过 Wiki 写入步骤（幂等补齐说明里仍会点名「不要调用 wiki_upsert」）
        assert "跳过本地 Wiki" in daily
        assert "沉淀到本地 Wiki" not in daily
        assert "feishu_doc_url" in daily

        with_vault = build_daily_prompt(NewsroomConfig(obsidian_vault="/tmp/vault"))
        assert "wiki_upsert" in with_vault and "沉淀到本地 Wiki" in with_vault

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


class TestContract:
    def test_manifest_roundtrip(self):
        manifest = contract.IssueManifest(
            issue_date="2026-09-16",
            title="AI 早报 #1",
            status="ready",
            sources_used=["AI 综合搜索"],
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

    def test_write_manifest_rejects_invalid_payload(self):
        with pytest.raises(ValueError):
            contract.write_manifest(contract.IssueManifest(issue_date="not-a-date"))

    def test_list_issues_skips_dirs_without_manifest(self, isolated_newsroom):
        ready = contract.IssueManifest(issue_date="2026-09-15", title="t1", status="ready")
        contract.write_manifest(ready)
        # 半成品目录：有产物无 manifest，不应出现在列表里
        (isolated_newsroom / "issues" / "2026-09-16").mkdir(parents=True)
        (isolated_newsroom / "issues" / "2026-09-16" / contract.ARTIFACT_DAILY_BRIEF).write_text(
            "x", encoding="utf-8"
        )

        issues = contract.list_issues()
        assert [i["issue_date"] for i in issues] == ["2026-09-15"]
        assert issues[0]["artifacts"]["daily-brief"] is False

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

    async def test_prompts_reference_workspace_paths(self, isolated_newsroom):
        from newsclaw.newsroom.prompts import build_daily_prompt, build_review_prompt

        daily = build_daily_prompt(load_config())
        review = build_review_prompt()
        root = str(isolated_newsroom)
        assert root in daily and "sources.yaml" in daily
        assert "manifest.json" in daily and "editorial-policy.md" in daily
        assert root in review and "feedback-export.json" in review and "reviews" in review
        # v8 起：知识沉淀由本地 Wiki（wiki_upsert）承担，飞书归档链接另立字段
        assert "wiki_upsert" in daily and "沉淀到本地 Wiki" in daily
        assert "feishu_doc_url" in daily

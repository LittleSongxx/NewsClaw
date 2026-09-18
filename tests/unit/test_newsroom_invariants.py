"""主线不变量的命名权威映射。

类名 TestI1–TestI9 与 ``newsroom/__init__.py`` 的不变量章一一对应：
那边改描述、这边改测试，必须一起动。多数场景在专题测试文件里已有更
深的覆盖，这里是薄场景版——保证每条不变量都有一个以它命名的红灯。
"""

from __future__ import annotations

import os

import pytest

from newsclaw.config import settings
from newsclaw.newsroom import contract, feedback


@pytest.fixture(autouse=True)
def isolated_newsroom(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "project_root", tmp_path)
    return tmp_path / "data" / "newsroom"


_VALID_ARTIFACT = (
    "今日要点：\n"
    "- 标题一｜说明｜https://example.com/news｜大模型\n"
    "- 标题二｜说明｜https://example.com/second｜公司动态\n"
    "- 标题三｜说明｜https://example.org/third｜开源项目\n\n"
    "### 标题方案\n1. 今日 AI 三件事\n2. 模型圈速览\n3. 开源动态一览\n\n"
    "### 基础信息\n- 标题：AI 早报｜三分钟看完今日要点\n- 摘要：示例。\n"
)


def _items() -> list[contract.NewsItem]:
    return [
        contract.NewsItem(title="标题一", url="https://example.com/news", source_name="AI 综合搜索"),
        contract.NewsItem(title="标题二", url="https://example.com/second", source_name="AI 综合搜索"),
        contract.NewsItem(title="标题三", url="https://example.org/third", source_name="公司与融资"),
    ]


def _write_artifacts(day: str, text: str = _VALID_ARTIFACT) -> None:
    folder = contract.issue_dir(day)
    folder.mkdir(parents=True, exist_ok=True)
    for name in (
        contract.ARTIFACT_DAILY_BRIEF,
        contract.ARTIFACT_XIAOHONGSHU,
        contract.ARTIFACT_WECHAT,
    ):
        (folder / name).write_text(text, encoding="utf-8")


def _ready_issue(day: str) -> None:
    from newsclaw.newsroom.sources import load_sources

    load_sources()
    _write_artifacts(day)
    contract.write_manifest(
        contract.IssueManifest(
            issue_date=day,
            title="t",
            status="ready",
            sources_used=["AI 综合搜索", "公司与融资"],
            items=_items(),
        )
    )


class TestI1ContractIsTheOnlyWritePath:
    """manifest 只能经契约函数落盘，Agent 不能自封 ready。"""

    def test_agent_cannot_self_declare_ready(self, isolated_newsroom):
        from newsclaw.newsroom.policy import write_manifest_from_agent

        with pytest.raises(ValueError, match="invalid manifest"):
            write_manifest_from_agent(
                "2026-09-16",
                '{"issue_date":"2026-09-16","title":"t","status":"ready"}',
            )


class TestI2DeliveryGate:
    """未 ready 不可投递；已出门的期次不被预算降级。"""

    def test_partial_blocked_and_delivered_shielded(self, isolated_newsroom):
        from newsclaw.newsroom.contract import demote_ready_on_budget_exceeded
        from newsclaw.newsroom.delivery import (
            mark_newsroom_delivered,
            newsroom_delivery_block_reason,
        )

        _ready_issue("2026-09-16")
        path = str(contract.issue_dir("2026-09-16") / contract.ARTIFACT_DAILY_BRIEF)
        mark_newsroom_delivered([path], '{"ok": true, "receipts": []}')
        assert demote_ready_on_budget_exceeded("2026-09-16") is False
        assert contract.read_manifest("2026-09-16").status == "ready"

        manifest = contract.read_manifest("2026-09-16")
        manifest.status = "partial"
        contract.write_manifest(manifest)
        assert newsroom_delivery_block_reason([path]) is not None


class TestI3FeedbackVerdict:
    """负反馈否决 ready，解除只能靠人改评。"""

    async def test_thumbs_down_then_re_rate(self, isolated_newsroom):
        _ready_issue("2026-09-16")
        await feedback.set_feedback("2026-09-16", -1, "太水")
        assert contract.read_manifest("2026-09-16").status == "rejected"
        await feedback.set_feedback("2026-09-16", 1, "改好了")
        assert contract.read_manifest("2026-09-16").status == "ready"


class TestI4DedupPoolCoversAllStatuses:
    """rejected 期的 URL 也算已见，防同链接重采。"""

    def test_rejected_issue_blocks_window(self, isolated_newsroom):
        from newsclaw.newsroom.items import collect_seen_urls

        _ready_issue("2026-09-10")
        rejected = contract.read_manifest("2026-09-10")
        rejected.status = "rejected"
        contract.write_manifest(rejected)

        seen = collect_seen_urls(before_date="2026-09-15", days=7)
        assert "https://example.com/news" in seen


class TestI5EvolutionCarriersSingleWritePath:
    """整文件重写类提案键直接判 invalid。"""

    def test_rewrite_root_key_rejected(self, isolated_newsroom, tmp_path):
        import json

        from newsclaw.newsroom.proposal import (
            STATUS_INVALID,
            load_latest_proposal,
            proposal_json_path,
        )

        path = proposal_json_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps({"policy_text": "# rewrite everything", "sources": []}),
            encoding="utf-8",
        )
        assert load_latest_proposal().status == STATUS_INVALID


class TestI6InjectionMirrorsDisk:
    """载体损坏：注入抛错（执行链路中止），机验变错误不崩溃。"""

    def test_corrupt_sources_fail_closed(self, isolated_newsroom):
        from newsclaw.newsroom.prompts import build_daily_injection_block
        from newsclaw.newsroom.sources import load_sources, sources_path

        load_sources()
        sources_path().write_text("{ unclosed mapping", encoding="utf-8")
        with pytest.raises(ValueError):
            build_daily_injection_block()
        manifest = contract.IssueManifest(
            issue_date="2026-09-16",
            title="t",
            status="ready",
            sources_used=["AI 综合搜索"],
        )
        assert any("cannot verify sources_used" in e for e in manifest.validate())


class TestI7SingleTaskMutex:
    """同任务并发被执行锁挡住（主线依赖的调度器保证）。"""

    def test_second_acquire_fails_while_holder_alive(self, tmp_path):
        from newsclaw.scheduler.locks import acquire_exec_lock, release_exec_lock

        lock_dir = tmp_path / "locks"
        first = acquire_exec_lock(
            "newsroom_daily_pipeline", lock_dir=lock_dir, pid=os.getpid()
        )
        assert first is not None
        try:
            second = acquire_exec_lock(
                "newsroom_daily_pipeline", lock_dir=lock_dir, pid=os.getpid()
            )
            assert second is None
        finally:
            release_exec_lock(first)


class TestI8QualityFloorsWriteTimeOnly:
    """质量下限只在写盘时生效，读旧期不翻旧账。"""

    def test_too_few_items_rejected_on_write_only(self, isolated_newsroom):
        from newsclaw.newsroom.sources import load_sources

        load_sources()
        _write_artifacts("2026-09-16")
        manifest = contract.IssueManifest(
            issue_date="2026-09-16",
            title="t",
            status="ready",
            sources_used=["AI 综合搜索", "公司与融资"],
            items=_items()[:1],
        )
        with pytest.raises(ValueError, match="quality floor"):
            contract.write_manifest(manifest)
        # 读路径（宽松）不按质量下限重判
        errors = manifest.validate()
        assert not any("quality floor" in e for e in errors)


class TestI9ItemFeedbackAdvisoryOnly:
    """条目反馈不改变期次状态。"""

    async def test_item_feedback_keeps_status(self, isolated_newsroom):
        contract.write_manifest(
            contract.IssueManifest(issue_date="2026-09-16", title="t", items=_items())
        )
        await feedback.set_item_feedback("2026-09-16", "https://example.com/news", -1, "水")
        assert contract.read_manifest("2026-09-16").status == "partial"

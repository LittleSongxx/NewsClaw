"""素材账本：URL 规范化、ready 撞链、投递门。"""

from __future__ import annotations

from newsclaw.config import settings
from newsclaw.newsroom import contract
from newsclaw.newsroom.delivery import newsroom_delivery_block_reason
from newsclaw.newsroom.items import normalize_url
from newsclaw.newsroom.prompts import build_daily_injection_block
from newsclaw.newsroom.sources import load_sources

import pytest


@pytest.fixture(autouse=True)
def isolated_newsroom(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "project_root", tmp_path)
    return tmp_path / "data" / "newsroom"


_VALID = (
    "今日要点：示例标题｜说明｜https://example.com/news｜大模型\n"
    "补充：结构校验要求去空白后足够长，并至少有一条可点开的 http 链接。\n"
    "无结果时必须写明无结果，不能交空壳。账本条目必须出现在正文里。\n"
)


def _items(url: str = "https://example.com/news") -> list[contract.NewsItem]:
    return [
        contract.NewsItem(
            title="示例标题",
            url=url,
            source_name="AI 综合搜索",
            one_liner="说明",
        )
    ]


def _write_artifacts(day: str, text: str = _VALID) -> None:
    folder = contract.issue_dir(day)
    folder.mkdir(parents=True, exist_ok=True)
    for name in (
        contract.ARTIFACT_DAILY_BRIEF,
        contract.ARTIFACT_XIAOHONGSHU,
        contract.ARTIFACT_WECHAT,
    ):
        (folder / name).write_text(text, encoding="utf-8")


def test_normalize_url_strips_tracking_and_www():
    left = normalize_url("http://www.Example.com/a/b/?utm_source=x&id=1")
    right = normalize_url("https://example.com/a/b?id=1")
    assert left == right


def test_ready_requires_items_when_artifacts_have_links(isolated_newsroom):
    load_sources()
    _write_artifacts("2026-09-15")
    manifest = contract.IssueManifest(
        issue_date="2026-09-15",
        title="t",
        status="ready",
        sources_used=["AI 综合搜索"],
    )
    errors = manifest.validate()
    assert any("requires non-empty items" in e for e in errors)


def test_ready_rejects_duplicate_item_url(isolated_newsroom):
    load_sources()
    _write_artifacts("2026-09-15")
    manifest = contract.IssueManifest(
        issue_date="2026-09-15",
        title="t",
        status="ready",
        sources_used=["AI 综合搜索"],
        items=_items() + _items("https://example.com/news?utm_source=dup"),
    )
    errors = manifest.validate()
    assert any("duplicate item url" in e for e in errors)


def test_ready_rejects_url_already_used_in_window(isolated_newsroom):
    load_sources()
    _write_artifacts("2026-09-10")
    contract.write_manifest(
        contract.IssueManifest(
            issue_date="2026-09-10",
            title="old",
            status="ready",
            sources_used=["AI 综合搜索"],
            items=_items(),
        )
    )
    _write_artifacts("2026-09-15")
    later = contract.IssueManifest(
        issue_date="2026-09-15",
        title="new",
        status="ready",
        sources_used=["AI 综合搜索"],
        items=_items("https://www.example.com/news"),
    )
    errors = later.validate()
    assert any("already used on 2026-09-10" in e for e in errors)


def test_ready_rejects_artifact_url_outside_ledger(isolated_newsroom):
    """稿里出现账本外链接必须被拒：不入账的链接会永久游离在去重池外。"""
    load_sources()
    extra = _VALID + "延伸阅读：https://example.com/extra\n"
    _write_artifacts("2026-09-15", text=extra)
    manifest = contract.IssueManifest(
        issue_date="2026-09-15",
        title="t",
        status="ready",
        sources_used=["AI 综合搜索"],
        items=_items(),
    )
    errors = manifest.validate()
    assert any("outside items[]" in e and "example.com/extra" in e for e in errors)


def test_ready_allows_asset_links_in_platform_artifacts_only(isolated_newsroom):
    """平台稿的配图建议资源外链豁免；日报总览仍严格要求全部入账。"""
    load_sources()
    with_asset = _VALID + "配图建议：https://images.example.com/cover.png\n"
    for name in (
        contract.ARTIFACT_DAILY_BRIEF,
        contract.ARTIFACT_XIAOHONGSHU,
        contract.ARTIFACT_WECHAT,
    ):
        folder = contract.issue_dir("2026-09-15")
        folder.mkdir(parents=True, exist_ok=True)
        text = with_asset if name != contract.ARTIFACT_DAILY_BRIEF else _VALID
        (folder / name).write_text(text, encoding="utf-8")
    manifest = contract.IssueManifest(
        issue_date="2026-09-15",
        title="t",
        status="ready",
        sources_used=["AI 综合搜索"],
        items=_items(),
    )
    assert manifest.validate() == []

    folder = contract.issue_dir("2026-09-15")
    (folder / contract.ARTIFACT_DAILY_BRIEF).write_text(with_asset, encoding="utf-8")
    errors = manifest.validate()
    assert any("outside items[]" in e and "cover.png" in e for e in errors)


def test_ready_accepts_new_url_and_injection_lists_seen(isolated_newsroom):
    load_sources()
    _write_artifacts("2026-09-10")
    contract.write_manifest(
        contract.IssueManifest(
            issue_date="2026-09-10",
            title="old",
            status="ready",
            sources_used=["AI 综合搜索"],
            items=_items(),
        )
    )
    _write_artifacts("2026-09-15", text=_VALID.replace("example.com/news", "example.com/fresh"))
    later = contract.IssueManifest(
        issue_date="2026-09-15",
        title="new",
        status="ready",
        sources_used=["AI 综合搜索"],
        items=_items("https://example.com/fresh"),
    )
    assert later.validate() == []
    block = build_daily_injection_block()
    assert "example.com/news" in block or "已见 URL" in block


def test_legacy_ready_without_items_still_loads(isolated_newsroom):
    load_sources()
    _write_artifacts("2026-09-10")
    day = contract.issue_dir("2026-09-10")
    (day / contract.MANIFEST_FILENAME).write_text(
        '{"issue_date":"2026-09-10","title":"old","status":"ready",'
        '"sources_used":["AI 综合搜索"],"wiki_entries":[],"feishu_doc_url":"",'
        '"feedback":{},"scores":{},"created_at":"","generator":"ai-news-editor"}',
        encoding="utf-8",
    )
    loaded, error = contract.load_manifest("2026-09-10")
    assert loaded is not None
    assert error is None
    assert loaded.status == "ready"
    assert loaded.items == []


def test_delivery_blocks_partial_issue(isolated_newsroom):
    load_sources()
    _write_artifacts("2026-09-15")
    contract.write_manifest(
        contract.IssueManifest(
            issue_date="2026-09-15",
            title="t",
            status="partial",
            sources_used=["AI 综合搜索"],
            items=_items(),
        )
    )
    path = str(contract.issue_dir("2026-09-15") / contract.ARTIFACT_DAILY_BRIEF)
    reason = newsroom_delivery_block_reason([path])
    assert reason is not None
    assert "ready" in reason


def test_delivery_allows_ready_issue(isolated_newsroom):
    load_sources()
    _write_artifacts("2026-09-15")
    contract.write_manifest(
        contract.IssueManifest(
            issue_date="2026-09-15",
            title="t",
            status="ready",
            sources_used=["AI 综合搜索"],
            items=_items(),
        )
    )
    path = str(contract.issue_dir("2026-09-15") / contract.ARTIFACT_DAILY_BRIEF)
    assert newsroom_delivery_block_reason([path]) is None


def test_delivery_ignores_unrelated_paths(isolated_newsroom):
    assert newsroom_delivery_block_reason(["/tmp/notes.md"]) is None


def _ready_issue(day: str) -> None:
    load_sources()
    _write_artifacts(day)
    contract.write_manifest(
        contract.IssueManifest(
            issue_date=day,
            title="t",
            status="ready",
            sources_used=["AI 综合搜索"],
            items=_items(),
        )
    )


def test_mark_delivered_records_timestamp_on_ok_receipt(isolated_newsroom):
    from newsclaw.newsroom.delivery import mark_newsroom_delivered

    _ready_issue("2026-09-15")
    path = str(contract.issue_dir("2026-09-15") / contract.ARTIFACT_DAILY_BRIEF)
    mark_newsroom_delivered([path], '{"ok": true, "receipts": []}')
    assert contract.read_manifest("2026-09-15").delivered_at != ""


def test_mark_delivered_ignores_failed_or_non_json_receipt(isolated_newsroom):
    from newsclaw.newsroom.delivery import mark_newsroom_delivered

    _ready_issue("2026-09-15")
    path = str(contract.issue_dir("2026-09-15") / contract.ARTIFACT_DAILY_BRIEF)
    mark_newsroom_delivered([path], '{"ok": false, "receipts": []}')
    mark_newsroom_delivered([path], "✅ 已发送到桌面（非 JSON 回执）")
    assert contract.read_manifest("2026-09-15").delivered_at == ""


def test_budget_demote_skips_delivered_issue(isolated_newsroom):
    """预算撞顶的降级不该追上已经推送出门的期次。"""
    from newsclaw.newsroom.delivery import mark_newsroom_delivered

    _ready_issue("2026-09-15")
    path = str(contract.issue_dir("2026-09-15") / contract.ARTIFACT_DAILY_BRIEF)
    mark_newsroom_delivered([path], '{"ok": true, "receipts": []}')

    assert contract.demote_ready_on_budget_exceeded("2026-09-15") is False
    assert contract.read_manifest("2026-09-15").status == "ready"

    # 未投递的 ready 期次照旧降级
    _ready_issue("2026-09-14")
    assert contract.demote_ready_on_budget_exceeded("2026-09-14") is True
    assert contract.read_manifest("2026-09-14").status == "partial"


def test_rejected_issue_still_blocks_window_urls(isolated_newsroom):
    """点踩降级不该把整期链接放回去重池。"""
    from newsclaw.newsroom.items import collect_seen_urls

    load_sources()
    _write_artifacts("2026-09-10")
    contract.write_manifest(
        contract.IssueManifest(
            issue_date="2026-09-10",
            title="old",
            status="ready",
            sources_used=["AI 综合搜索"],
            items=_items(),
        )
    )
    rejected, _ = contract.load_manifest("2026-09-10")
    rejected.status = "rejected"
    contract.write_manifest(rejected)

    seen = collect_seen_urls(before_date="2026-09-15", days=7)
    assert "https://example.com/news" in seen


def test_partial_issue_with_items_blocks_window_urls(isolated_newsroom):
    from newsclaw.newsroom.items import collect_seen_urls

    load_sources()
    contract.write_manifest(
        contract.IssueManifest(
            issue_date="2026-09-10",
            title="partial",
            status="partial",
            sources_used=["AI 综合搜索"],
            items=_items(),
        )
    )
    seen = collect_seen_urls(before_date="2026-09-15", days=7)
    assert "https://example.com/news" in seen


def test_issue_dir_without_manifest_falls_back_to_brief(isolated_newsroom):
    """跑了一半的现场（无 manifest）也要靠日报稿挡住已见链接。"""
    from newsclaw.newsroom.items import collect_seen_urls

    load_sources()
    _write_artifacts("2026-09-10")
    seen = collect_seen_urls(before_date="2026-09-15", days=7)
    assert "https://example.com/news" in seen


def test_injection_seen_list_covers_full_window(isolated_newsroom):
    """注入清单不得截断：机验对账用全量，注入端看不见就会撞车。"""
    from newsclaw.newsroom.items import format_seen_items_for_prompt

    load_sources()
    many = [
        contract.NewsItem(title=f"标题{i}", url=f"https://example.com/story/{i}", source_name="")
        for i in range(45)
    ]
    contract.write_manifest(
        contract.IssueManifest(
            issue_date="2026-09-10",
            title="big",
            status="partial",
            sources_used=["AI 综合搜索"],
            items=many,
        )
    )
    text = format_seen_items_for_prompt(before_date="2026-09-15", days=7)
    assert "已省略" not in text
    assert "example.com/story/44" in text

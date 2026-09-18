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


#: 合规产物样张：三条外链（与 _items 对齐）+ 平台稿格式钉要求的两个小节。
_VALID = (
    "今日要点：\n"
    "- 示例标题｜说明｜https://example.com/news｜大模型\n"
    "- 第二条标题｜说明｜https://example.com/second｜公司动态\n"
    "- 第三条标题｜说明｜https://example.org/third｜开源项目\n\n"
    "### 标题方案\n1. 今日 AI 三件事\n2. 模型圈速览\n3. 开源动态一览\n\n"
    "### 基础信息\n- 标题：AI 早报｜三分钟看完今日要点\n- 摘要：示例摘要。\n"
)


def _items(url: str = "https://example.com/news") -> list[contract.NewsItem]:
    return [
        contract.NewsItem(
            title="示例标题",
            url=url,
            source_name="AI 综合搜索",
            one_liner="说明",
        ),
        contract.NewsItem(
            title="第二条标题",
            url="https://example.com/second",
            source_name="AI 综合搜索",
            one_liner="说明",
        ),
        contract.NewsItem(
            title="第三条标题",
            url="https://example.org/third",
            source_name="公司与融资",
            one_liner="说明",
        ),
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
        sources_used=["AI 综合搜索", "公司与融资"],
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
        sources_used=["AI 综合搜索", "公司与融资"],
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
            sources_used=["AI 综合搜索", "公司与融资"],
            items=_items(),
        )
    )
    _write_artifacts("2026-09-15")
    later = contract.IssueManifest(
        issue_date="2026-09-15",
        title="new",
        status="ready",
        sources_used=["AI 综合搜索", "公司与融资"],
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
        sources_used=["AI 综合搜索", "公司与融资"],
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
        sources_used=["AI 综合搜索", "公司与融资"],
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
            sources_used=["AI 综合搜索", "公司与融资"],
            items=_items(),
        )
    )
    fresh_text = (
        _VALID.replace("example.com/news", "example.com/fresh1")
        .replace("example.com/second", "example.com/fresh2")
        .replace("example.org/third", "example.org/fresh3")
    )
    _write_artifacts("2026-09-15", text=fresh_text)
    later = contract.IssueManifest(
        issue_date="2026-09-15",
        title="new",
        status="ready",
        sources_used=["AI 综合搜索", "公司与融资"],
        items=[
            contract.NewsItem(
                title="全新标题一", url="https://example.com/fresh1", source_name="AI 综合搜索"
            ),
            contract.NewsItem(
                title="全新标题二", url="https://example.com/fresh2", source_name="AI 综合搜索"
            ),
            contract.NewsItem(
                title="全新标题三", url="https://example.org/fresh3", source_name="公司与融资"
            ),
        ],
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
            sources_used=["AI 综合搜索", "公司与融资"],
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
            sources_used=["AI 综合搜索", "公司与融资"],
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
            sources_used=["AI 综合搜索", "公司与融资"],
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


def test_budget_demote_falls_back_to_recent_issue(isolated_newsroom):
    """跨午夜运行：查询日无 manifest 时按 mtime 兜底到刚写盘的期次。"""
    _ready_issue("2026-09-10")
    assert contract.demote_ready_on_budget_exceeded("2099-01-01") is True
    assert contract.read_manifest("2026-09-10").status == "partial"


def test_latest_issue_date_within_respects_age(isolated_newsroom):
    import os
    from datetime import datetime, timedelta

    _ready_issue("2026-09-10")
    path = contract.issue_dir("2026-09-10") / contract.MANIFEST_FILENAME
    stale = (datetime.now() - timedelta(hours=30)).timestamp()
    os.utime(path, (stale, stale))
    assert contract.latest_issue_date_within(25) is None
    assert contract.demote_ready_on_budget_exceeded("2099-01-01") is False


def test_injection_block_stamps_run_issue_date(isolated_newsroom):
    """注入块必须印记本期 issue_date，跨午夜时目录名以此为准。"""
    from datetime import date

    load_sources()
    block = build_daily_injection_block()
    assert "本期 issue_date：" in block
    assert date.today().isoformat() in block


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
            sources_used=["AI 综合搜索", "公司与融资"],
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
            sources_used=["AI 综合搜索", "公司与融资"],
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
            sources_used=["AI 综合搜索", "公司与融资"],
            items=many,
        )
    )
    text = format_seen_items_for_prompt(before_date="2026-09-15", days=7)
    assert "已省略" not in text
    assert "example.com/story/44" in text

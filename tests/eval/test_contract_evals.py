"""离线 eval：把「旧产物兼容」与「当前契约可 ready」钉成可执行基线。

为什么需要这一层
================
- E1/E2：2026-09-17 首期真实产物是在旧模板下写的——小红书标题候选带行内
  代码反引号与「（推荐，9 字）」注解，公众号稿用的是 ``## 标题`` 而不是技
  能模板的 ``### 基础信息`` + ``- 标题：``。这些是历史资产，契约只能向前兼
  容，不能把旧期次读成 invalid。
- E3：合成一期「按今天的标准写就该 ready」的 golden 期次，钉住
  ``write_manifest`` 的完整机验面（账本 ⊆⊆、质量下限、平台稿格式钉）真的
  能通过——防止门越拧越紧却没人能过。
- E4–E6：三个负例证明门确实在拦（items 不足 / 账本外 URL / 标题撞窗）。
- E7：确定性 rubric，把「稿子像不像样」降成一张可打印的分数表。

fixtures 说明
=============
- ``fixtures/issue-2026-09-17/`` 从 ``data/newsroom/issues/2026-09-17/``
  原样复制（只读、未改内容），是带历史瑕疵的真实旧期次样本。
- ``fixtures/golden-issue/`` 是合成期次：manifest 的 date / items 字段与
  sources.yaml 默认信源对齐，三产物按技能模板的格式钉写。
本套件零网络、零 ``data/`` 依赖，可在 CI 的任意干净 checkout 上运行。
"""

from __future__ import annotations

import dataclasses
import json
import re
from datetime import date
from pathlib import Path

import pytest

from newsclaw.config import settings
from newsclaw.newsroom import contract
from newsclaw.newsroom.contract import (
    ARTIFACT_DAILY_BRIEF,
    ARTIFACT_WECHAT,
    ARTIFACT_XIAOHONGSHU,
    XIAOHONGSHU_TITLE_MAX_CHARS,
    IssueManifest,
    _section_lines,
    _wechat_title,
    _xhs_title_candidates,
)
from newsclaw.newsroom.items import extract_urls_from_text

FIXTURES = Path(__file__).resolve().parent / "fixtures"
OLD_ISSUE = FIXTURES / "issue-2026-09-17"
GOLDEN_ISSUE = FIXTURES / "golden-issue"
OLD_DATE = "2026-09-17"
GOLDEN_DATE = "2026-09-18"
ARTIFACTS = (ARTIFACT_DAILY_BRIEF, ARTIFACT_XIAOHONGSHU, ARTIFACT_WECHAT)


@pytest.fixture()
def isolated_newsroom(tmp_path, monkeypatch) -> Path:
    """把 newsroom 数据根指到 tmp：测试不读也不写仓库里的 data/。"""
    monkeypatch.setattr(settings, "project_root", tmp_path)
    return tmp_path / "data" / "newsroom"


def _artifact_texts(folder: Path) -> dict[str, str]:
    return {name: (folder / name).read_text(encoding="utf-8") for name in ARTIFACTS}


def _install_issue(source: Path, target_date: str) -> Path:
    """把 fixture 期次装进当前（被 monkeypatch 的）newsroom/issues 下。"""
    day = contract.issue_dir(target_date)
    day.mkdir(parents=True, exist_ok=True)
    for name in (*ARTIFACTS, contract.MANIFEST_FILENAME):
        (day / name).write_text(
            (source / name).read_text(encoding="utf-8"), encoding="utf-8"
        )
    return day


def _golden_manifest() -> IssueManifest:
    raw = (GOLDEN_ISSUE / contract.MANIFEST_FILENAME).read_text(encoding="utf-8")
    return IssueManifest.from_dict(json.loads(raw))


def test_fixtures_are_present_and_representative() -> None:
    """fixtures 存在，且旧样本确实是「旧 schema」（partial + 无 items）。"""
    assert (OLD_ISSUE / contract.MANIFEST_FILENAME).is_file()
    assert (GOLDEN_ISSUE / contract.MANIFEST_FILENAME).is_file()
    for name in ARTIFACTS:
        assert (OLD_ISSUE / name).is_file(), name
        assert (GOLDEN_ISSUE / name).is_file(), name
    old = json.loads((OLD_ISSUE / contract.MANIFEST_FILENAME).read_text(encoding="utf-8"))
    assert old["status"] == "partial"
    assert not old.get("items")


class TestE1OldArtifactsPassFormatPins:
    """E1：真实旧产物的格式钉错误必须为空。"""

    def test_old_artifacts_have_no_format_pin_errors(self) -> None:
        manifest = IssueManifest(issue_date=OLD_DATE, title="AI 早报 #1", status="partial")
        assert manifest._artifact_format_errors(_artifact_texts(OLD_ISSUE)) == []

    def test_old_xhs_titles_are_measured_on_title_body_only(self) -> None:
        candidates = _xhs_title_candidates(_artifact_texts(OLD_ISSUE)[ARTIFACT_XIAOHONGSHU])
        assert candidates == [
            "AI圈这周真的疯了🤯",
            "GPT-5.5下个月就没了⚡",
            "1.2万亿美元！AI估值又破纪录💰",
        ]
        for title in candidates:
            width = sum(1 for ch in title if not ch.isspace())
            assert width <= XIAOHONGSHU_TITLE_MAX_CHARS, title
        # 反引号与「（推荐，9 字）」注解不属于标题本体
        assert all("`" not in title and "（" not in title for title in candidates)

    def test_old_wechat_title_uses_legacy_heading_fallback(self) -> None:
        title = _wechat_title(_artifact_texts(OLD_ISSUE)[ARTIFACT_WECHAT])
        assert title is not None
        assert title.startswith("AI 早报 #1｜OpenAI 估值冲 1.2 万亿美元")


class TestE2OldManifestLoadsLeniently:
    """E2：旧 manifest 宽松加载无 error，列表里是 partial 而不是 invalid。"""

    def test_old_manifest_loads_without_error(self, isolated_newsroom) -> None:
        _install_issue(OLD_ISSUE, OLD_DATE)
        manifest, error = contract.load_manifest(OLD_DATE)
        assert error is None
        assert manifest is not None
        assert manifest.status == "partial"
        assert manifest.items == []
        assert manifest.sources_used == [
            "AI 综合搜索",
            "前沿模型动态",
            "公司与融资",
            "开源项目热点",
        ]

    def test_old_issue_lists_as_partial_not_invalid(self, isolated_newsroom) -> None:
        _install_issue(OLD_ISSUE, OLD_DATE)
        rows = contract.list_issues()
        assert len(rows) == 1
        assert rows[0]["issue_date"] == OLD_DATE
        assert rows[0]["status"] == "partial"
        assert rows[0]["manifest_error"] is None
        assert rows[0]["artifacts"] == {
            "daily-brief": True,
            "xiaohongshu": True,
            "wechat": True,
        }


class TestE3GoldenIssueSatisfiesReadyContract:
    """E3：golden 期次按当前口径 validate(strict_quality=True) 必须零错误。"""

    def test_golden_manifest_is_ready_clean(self, isolated_newsroom) -> None:
        day = _install_issue(GOLDEN_ISSUE, GOLDEN_DATE)
        manifest = _golden_manifest()
        assert manifest.status == "ready"
        assert date.fromisoformat(manifest.issue_date) == date(2026, 9, 18)
        assert len(manifest.items) == 3
        assert {item.source_name for item in manifest.items} <= set(manifest.sources_used)
        assert all(item.url.startswith("https://") for item in manifest.items)
        assert len({item.url for item in manifest.items}) == 3
        errors = manifest.validate(
            artifact_dir=day,
            require_item_ledger=True,
            strict_quality=True,
        )
        assert errors == []

    def test_golden_manifest_roundtrips_through_write_manifest(
        self, isolated_newsroom
    ) -> None:
        _install_issue(GOLDEN_ISSUE, GOLDEN_DATE)
        manifest = _golden_manifest()
        written = contract.write_manifest(manifest)
        assert written.is_file()
        reloaded, error = contract.load_manifest(GOLDEN_DATE)
        assert error is None
        assert reloaded is not None
        assert reloaded.status == "ready"
        assert [item.url for item in reloaded.items] == [i.url for i in manifest.items]


class TestE4UnderfilledLedgerIsRejected:
    """E4：有外链的期次 items < 3 必须被质量下限拦住。"""

    def test_two_items_fail_quality_floor(self, isolated_newsroom) -> None:
        day = _install_issue(GOLDEN_ISSUE, GOLDEN_DATE)
        full = _golden_manifest()
        manifest = dataclasses.replace(full, items=full.items[:2])
        errors = manifest.validate(
            artifact_dir=day, require_item_ledger=True, strict_quality=True
        )
        assert any("only 2 item(s)" in error for error in errors), errors


class TestE5UrlOutsideLedgerIsRejected:
    """E5：稿里出现账本外 URL 必须被反向 ⊆ 机验拦住。"""

    def test_extra_url_in_daily_brief_fails_reverse_ledger(
        self, isolated_newsroom
    ) -> None:
        day = _install_issue(GOLDEN_ISSUE, GOLDEN_DATE)
        brief = day / ARTIFACT_DAILY_BRIEF
        brief.write_text(
            brief.read_text(encoding="utf-8")
            + "\n- 附注：另一条未入账素材 https://outside.example/story\n",
            encoding="utf-8",
        )
        errors = _golden_manifest().validate(
            artifact_dir=day, require_item_ledger=True, strict_quality=True
        )
        assert any(
            "outside items[]" in error and "outside.example" in error for error in errors
        ), errors


class TestE6TitleCollisionIsRejected:
    """E6：窗口内已见标题（同题换链接重发）必须被标题撞窗拦住。"""

    def test_title_seen_in_window_blocks_ready(self, isolated_newsroom) -> None:
        _install_issue(GOLDEN_ISSUE, GOLDEN_DATE)
        manifest = _golden_manifest()
        duplicate_title = manifest.items[0].title
        previous = {
            "issue_date": OLD_DATE,
            "title": "AI 早报 #1",
            "status": "partial",
            "items": [
                {
                    "title": duplicate_title,
                    "url": "https://example.com/news/2026-09-17/old-story",
                    "source_name": "AI 综合搜索",
                }
            ],
        }
        prev_day = contract.issue_dir(OLD_DATE)
        prev_day.mkdir(parents=True, exist_ok=True)
        (prev_day / contract.MANIFEST_FILENAME).write_text(
            json.dumps(previous, ensure_ascii=False), encoding="utf-8"
        )
        errors = manifest.validate(
            artifact_dir=contract.issue_dir(GOLDEN_DATE),
            require_item_ledger=True,
            strict_quality=True,
        )
        assert any(
            f"item title already used on {OLD_DATE}" in error for error in errors
        ), errors
        assert any("同题重发" in error for error in errors), errors


class TestE7DeterministicRubric:
    """E7：确定性 rubric（5 项 × 20 分）并把分数表打印出来。"""

    def _rubric_rows(self) -> list[tuple[str, bool, int]]:
        texts = _artifact_texts(GOLDEN_ISSUE)
        manifest = _golden_manifest()

        xhs = texts[ARTIFACT_XIAOHONGSHU]
        candidates = _xhs_title_candidates(xhs)
        hashtags = re.findall(r"#\S+", "\n".join(_section_lines(xhs, "话题标签")))

        wechat = texts[ARTIFACT_WECHAT]
        wechat_ok = (
            _wechat_title(wechat) is not None
            and "- 摘要：" in wechat
            and bool(_section_lines(wechat, "正文"))
        )

        ledger_urls = {item.url for item in manifest.items}
        brief_urls = extract_urls_from_text(texts[ARTIFACT_DAILY_BRIEF])
        brief_ledger_links = [url for url in brief_urls if url in ledger_urls]

        return [
            ("xhs 标题候选数 == 3", len(candidates) == 3, 20),
            (
                f"xhs 候选全部 <= {XIAOHONGSHU_TITLE_MAX_CHARS} 字",
                bool(candidates)
                and all(
                    sum(1 for ch in title if not ch.isspace())
                    <= XIAOHONGSHU_TITLE_MAX_CHARS
                    for title in candidates
                ),
                20,
            ),
            ("xhs 话题标签 >= 3", len(hashtags) >= 3, 20),
            ("wechat 含标题 + 摘要 + 正文", wechat_ok, 20),
            ("daily-brief 账本内来源链接 >= 3", len(brief_ledger_links) >= 3, 20),
        ]

    def test_rubric_passes_and_prints_table(self, capsys) -> None:
        rows = self._rubric_rows()
        total = sum(points for _name, ok, points in rows if ok)
        lines = ["E7 确定性 rubric（fixtures/golden-issue）", "-" * 52]
        for name, ok, points in rows:
            lines.append(
                f"{'PASS' if ok else 'FAIL'} | {name} | {points if ok else 0}/{points}"
            )
        lines.append("-" * 52)
        lines.append(f"TOTAL {total}/100")
        table = "\n".join(lines)
        print(table)
        assert "PASS" in table
        assert total == 100, table

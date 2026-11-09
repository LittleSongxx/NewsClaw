"""平台稿格式钉对真实 Agent 输出的兼容性回归。

背景：2026-09-17 首期真实产物在当前的严格格式钉下曾被误杀 3 条——小红书
标题候选带着行内代码反引号与「（推荐，9 字）」这类注解，公众号稿用的是
``## 标题`` 而不是技能模板里的 ``### 基础信息`` + ``- 标题：``。格式钉
应该量「标题本体」，而不是惩罚 Agent 的标注习惯。
"""

from __future__ import annotations

from newsclaw.newsroom.contract import (
    XIAOHONGSHU_TITLE_MAX_CHARS,
    IssueManifest,
    _wechat_title,
    _xhs_title_candidates,
)


def _manifest() -> IssueManifest:
    return IssueManifest(
        issue_date="2026-09-17",
        title="AI 早报 #1",
        status="partial",
        sources_used=["AI 综合搜索"],
        wiki_entries=[],
    )


XHS_REAL = """
## 小红书笔记

### 标题方案

1. `AI圈这周真的疯了🤯`（推荐，9 字）
2. `GPT-5.5下个月就没了⚡`（13 字，痛点法）
3. `1.2万亿美元！AI估值又破纪录💰`（17 字，数字法）

### 正文
"""

WECHAT_REAL = """
# 公众号稿｜AI 早报 #1

## 标题

**AI 早报 #1｜OpenAI 估值冲 1.2 万亿美元，GPT-5.5 官宣下线**

> 备选标题：
> - 《一晚三件事》

## 摘要

正文。
"""


class TestXhsTitlePins:
    def test_real_artifact_candidates_are_measured_on_the_title_body(self):
        candidates = _xhs_title_candidates(XHS_REAL)
        assert candidates == [
            "AI圈这周真的疯了🤯",
            "GPT-5.5下个月就没了⚡",
            "1.2万亿美元！AI估值又破纪录💰",
        ]
        assert all(len(c) <= XIAOHONGSHU_TITLE_MAX_CHARS for c in candidates)

    def test_annotations_and_backticks_are_stripped_before_measuring(self):
        text = "### 标题方案\n1. **`一个很长的标题一共十五个字哈哈`**（数字法，15 字）\n"
        assert _xhs_title_candidates(text) == ["一个很长的标题一共十五个字哈哈"]

    def test_nested_annotations_do_not_leak(self):
        text = "### 标题方案\n1. 标题本体（推荐（编辑注））\n"
        assert _xhs_title_candidates(text) == ["标题本体"]

    def test_quoted_title_wrappers_are_stripped(self):
        text = "### 标题方案\n1. 《今日 AI 三件事》\n"
        assert _xhs_title_candidates(text) == ["今日 AI 三件事"]

    def test_missing_section_yields_no_candidates(self):
        assert _xhs_title_candidates("## 正文\n随便写点") == []


class TestWechatTitlePins:
    def test_primary_section_still_works(self):
        text = "### 基础信息\n- 标题：AI 早报｜三分钟看完今日要点\n- 摘要：x\n"
        assert _wechat_title(text) == "AI 早报｜三分钟看完今日要点"

    def test_legacy_heading_section_is_tolerated(self):
        assert _wechat_title(WECHAT_REAL) == (
            "AI 早报 #1｜OpenAI 估值冲 1.2 万亿美元，GPT-5.5 官宣下线"
        )

    def test_missing_title_returns_none(self):
        assert _wechat_title("## 正文\n没有标题的小节") is None


class TestFormatPinErrors:
    def test_real_artifacts_produce_no_pin_errors(self):
        texts = {
            "xiaohongshu.md": XHS_REAL,
            "wechat.md": WECHAT_REAL,
            "daily-brief.md": "## 入选\n1. 一条 http://example.com/a\n",
        }
        assert _manifest()._artifact_format_errors(texts) == []

    def test_over_long_title_after_normalization_still_fails(self):
        long_title = "这是一个明显超过二十个字符限制的小红书标题候选"
        assert len(long_title) > XIAOHONGSHU_TITLE_MAX_CHARS
        texts = {
            "xiaohongshu.md": f"### 标题方案\n1. `{long_title}`（21 字）\n",
            "wechat.md": WECHAT_REAL,
        }
        errors = _manifest()._artifact_format_errors(texts)
        assert any("too long" in e for e in errors)

    def test_missing_platform_sections_still_fail(self):
        errors = _manifest()._artifact_format_errors(
            {"xiaohongshu.md": "### 正文\n无", "wechat.md": "# 正文\n无"}
        )
        assert any("标题方案" in e for e in errors)
        assert any("基础信息" in e for e in errors)
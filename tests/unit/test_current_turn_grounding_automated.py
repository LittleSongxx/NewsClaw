"""当前轮引用接地：占位 URL 与自动会话（调度任务 / 子 Agent）不参与接地。

2026-11-09 两次实跑里，采集子 Agent 的任务包带 ``https://...原始报道页``
这条输出示例，它被当成“用户明确引用的 URL”，导致子 Agent 的真实 web_fetch
全部被接地守卫拦掉、三个采集员返回空、主 Agent 只能自己采集——单期 461 万
输入 token 的直接原因之一。
"""

from __future__ import annotations

from newsclaw.core.current_turn import CurrentTurnInput, _url_objects_from_text

COLLECTOR_BRIEF = """【采集任务 · AI 早报 2026-11-09 期 · 第 1 组】

本组信源（共 1 条）：
- name=AI 综合搜索 | kind=search | weight=4 | query=AI 人工智能 大模型 最新新闻

输出契约：只返回一个 JSON 数组。每项格式：
{"title": "原标题", "url": "https://...原始报道页", "source_name": "AI 综合搜索"}

无结果则返回 [] 并另起一行写「无结果」。
"""


class TestPlaceholderUrls:
    def test_template_placeholder_is_not_extracted(self):
        assert _url_objects_from_text("每项格式：https://...原始报道页") == []

    def test_manifest_schema_placeholder_is_not_extracted(self):
        text = '{"url": "https://...（必须出现在三份稿里）", "title": "x"}'
        assert _url_objects_from_text(text) == []

    def test_collector_brief_does_not_ground_web_fetch(self):
        turn = CurrentTurnInput.from_inputs(COLLECTOR_BRIEF)
        assert turn.urls == ()
        assert turn.validate_tool_call(
            "web_fetch", {"url": "https://www.qbitai.com/2026/11/508694.html"}
        ) is None

    def test_real_urls_are_still_extracted(self):
        turn = CurrentTurnInput.from_inputs("读这个 https://www.qbitai.com/2026/11/a.html")
        assert len(turn.urls) == 1
        assert turn.urls[0].value == "https://www.qbitai.com/2026/11/a.html"


class TestAutomatedSessions:
    def test_automated_turn_skips_grounding(self):
        turn = CurrentTurnInput.from_inputs(
            "读这个 https://example.com/new", automated=True
        )
        assert turn.validate_tool_call("web_fetch", {"url": "https://example.com/old"}) is None

    def test_automated_turn_injects_no_prompt_block(self):
        turn = CurrentTurnInput.from_inputs(
            "读这个 https://example.com/new", automated=True
        )
        assert turn.prompt_block() == ""
        assert turn.inject_into_message("hi") == "hi"

    def test_automated_flag_defaults_false(self):
        turn = CurrentTurnInput.from_inputs("读这个 https://example.com/new")
        assert turn.automated is False
        assert turn.prompt_block() != ""

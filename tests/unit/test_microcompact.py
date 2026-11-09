"""microcompact：按数量清理历史工具结果（零 LLM 成本）。

2026-11-09 的真实运行里，单条结果的 8000 字符阈值挡不住「每条 2–3KB、
累计 100+ 条」的累积：51 轮 ReAct 把消息上下文从 3.2k 推到 99.6k token，
每轮重发，单期输入 461 万 token。这里钉住新的「保留最近 N 条 + 旧结果压预览」
行为，防止回退。
"""

from __future__ import annotations

import time

from newsclaw.core.microcompact import (
    KEEP_RECENT_TOOL_RESULTS,
    microcompact,
)


def _tool_msg(tool_use_id: str, content: str, tool_name: str = "web_search") -> dict:
    return {
        "role": "user",
        "content": [
            {
                "type": "tool_result",
                "tool_use_id": tool_use_id,
                "tool_name": tool_name,
                "content": content,
            }
        ],
    }


def _assistant_msg(text: str = "ok") -> dict:
    return {"role": "assistant", "content": [{"type": "text", "text": text}]}


def _history(count: int, size: int) -> list[dict]:
    """每条结果内容唯一（全同内容会先被重复合并，测不到按数量压缩）。"""
    messages: list[dict] = []
    for index in range(count):
        body = f"x{index}-" + "y" * max(0, size - len(str(index)) - 2)
        assert len(body) == size, (len(body), size)
        messages.append(_tool_msg(f"t{index}", body))
        messages.append(_assistant_msg())
    return messages


def _contents(messages: list[dict], role: str = "user") -> list[str]:
    return [
        block["content"]
        for msg in messages
        if msg["role"] == role
        for block in msg["content"]
        if block.get("type") == "tool_result"
    ]


class TestCountBasedCompaction:
    def test_old_medium_results_are_compacted_by_count_not_size(self):
        messages = _history(20, 2500)  # 每条低于旧阈值 8000，但累计 50KB
        before = sum(len(c) for c in _contents(messages))
        microcompact(messages)
        contents = _contents(messages)
        after = sum(len(c) for c in contents)

        kept = contents[-KEEP_RECENT_TOOL_RESULTS:]
        assert all(len(c) == 2500 for c in kept), "最近 N 条必须原样保留"
        old = contents[:-KEEP_RECENT_TOOL_RESULTS]
        assert old, "应有旧结果被压缩"
        assert all("旧工具结果已压缩" in c for c in old)
        assert all(len(c) < 700 for c in old)
        assert after < before * 0.6, f"压缩后应显著变小: {before} -> {after}"

    def test_recent_results_never_truncated_even_if_huge(self):
        messages = _history(4, 20000)
        microcompact(messages)
        assert all(len(c) == 20000 for c in _contents(messages))

    def test_short_old_results_left_alone(self):
        messages = _history(20, 300)  # 全部低于 min_compact_chars
        microcompact(messages)
        assert all(len(c) == 300 for c in _contents(messages))

    def test_keep_recent_is_configurable(self):
        messages = _history(12, 2500)
        microcompact(messages, keep_recent_tool_results=3)
        contents = _contents(messages)
        assert [len(c) for c in contents[-3:]] == [2500, 2500, 2500]
        assert all("旧工具结果已压缩" in c for c in contents[:-3])

    def test_empty_and_non_block_messages_are_safe(self):
        messages = [
            {"role": "user", "content": "纯文本"},
            {"role": "assistant", "content": [{"type": "text", "text": "ok"}]},
        ]
        microcompact(messages)
        assert messages[0]["content"] == "纯文本"


class TestPreservesContract:
    def test_tool_use_ids_are_preserved(self):
        messages = _history(20, 2500)
        microcompact(messages)
        ids = [
            block["tool_use_id"]
            for msg in messages
            for block in msg["content"]
            if block.get("type") == "tool_result"
        ]
        assert ids == [f"t{i}" for i in range(20)]

    def test_duplicate_old_results_are_merged(self):
        messages = [
            _tool_msg("t1", "same-content-" * 100),
            _assistant_msg(),
            _tool_msg("t2", "same-content-" * 100),
            _assistant_msg(),
            _tool_msg("t3", "other-" * 100),
            _assistant_msg(),
            _tool_msg("t4", "recent-A" * 300),
            _assistant_msg(),
            _tool_msg("t5", "recent-B" * 300),
        ]
        microcompact(messages, keep_recent_tool_results=2)
        contents = _contents(messages)
        assert "已合并" in contents[1]
        assert contents[3] == "recent-A" * 300
        assert contents[4] == "recent-B" * 300

    def test_expired_result_cleared_when_timestamp_present(self):
        messages = [
            {
                "role": "user",
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": "old",
                        "tool_name": "web_fetch",
                        "content": "z" * 500,
                        "_timestamp": time.time() - 3600,
                    }
                ],
            },
            _assistant_msg(),
            _tool_msg("recent", "recent result " * 40),
        ]
        microcompact(messages, tool_result_expiry_s=60, keep_recent_tool_results=1)
        assert _contents(messages)[0] == "[expired tool result]"

    def test_old_thinking_blocks_removed_but_recent_kept(self):
        old_thinking = "very long thinking " * 50
        recent_thinking = "recent thinking " * 50
        messages = [
            {"role": "assistant", "content": [{"type": "thinking", "thinking": old_thinking}]},
            _tool_msg("t1", "r" * 200),
            _assistant_msg(),
            {"role": "assistant", "content": [{"type": "thinking", "thinking": recent_thinking}]},
        ]
        microcompact(messages)
        assert messages[0]["content"][0]["thinking"] == "[thinking removed by microcompact]"
        assert messages[3]["content"][0]["thinking"] == recent_thinking


class TestFingerprintPrecision:
    def test_long_results_with_same_prefix_are_not_merged(self):
        """只哈希前 4000 字符会把同站点不同页的样板开头误判成重复。"""
        prefix = "nav-boilerplate " * 300  # > 4000 chars，两条共享
        first = prefix + "REAL-CONTENT-A " * 200
        second = prefix + "REAL-CONTENT-B " * 200
        messages = [
            _tool_msg("t1", first),
            _assistant_msg(),
            _tool_msg("t2", second),
            _assistant_msg(),
            _tool_msg("t3", "recent result " * 100),
        ]
        microcompact(messages, keep_recent_tool_results=1)
        contents = _contents(messages)
        assert "已合并" not in contents[0]
        assert "已合并" not in contents[1]
        # 两条都应走「预览压缩」而不是被替换成合并标记
        assert "旧工具结果已压缩" in contents[0]
        assert "旧工具结果已压缩" in contents[1]

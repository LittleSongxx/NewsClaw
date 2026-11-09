"""
Microcompact — 请求前轻量上下文清理

零 LLM 调用成本的上下文瘦身策略，在发送 API 请求前执行:
1. 按「保留最近 N 条」压缩历史工具结果（本模块的主力，见 2026-11-09 实跑复盘）
2. 过期工具结果清空（块带 ``_timestamp`` 时生效）
3. 重复/相似工具结果合并
4. 旧 thinking 块移除
5. 旧 tool_use 参数裁剪

参考 Claude Code 的 microcompact / tool-result clearing 策略。
"""

from __future__ import annotations

import hashlib
import logging
import time

logger = logging.getLogger(__name__)

TOOL_RESULT_EXPIRY_SECONDS = 600  # 10 分钟
#: 最近 N 个工具结果原样保留。工具结果是最占上下文的部分，但最近几条通常
#: 正在被决策使用；更早的结果只留预览，需要时 Agent 可以重新调用工具。
KEEP_RECENT_TOOL_RESULTS = 8
#: 旧工具结果短于该字符数时不压缩（压缩收益低于碎片化成本）。
COMPACT_MIN_CHARS = 1200
#: 旧工具结果压缩后保留的开头字符数。
COMPACT_PREVIEW_CHARS = 400


def microcompact(
    messages: list[dict],
    *,
    tool_result_expiry_s: float = TOOL_RESULT_EXPIRY_SECONDS,
    keep_recent_tool_results: int = KEEP_RECENT_TOOL_RESULTS,
    min_compact_chars: int = COMPACT_MIN_CHARS,
    compact_preview_chars: int = COMPACT_PREVIEW_CHARS,
    current_time: float | None = None,
) -> list[dict]:
    """对消息列表执行轻量清理（零 LLM 成本，原地修改）。

    2026-11-09 的真实运行暴露了旧策略的盲区：单条结果 8000 字符的阈值挡不住
    「每条 2–3KB、累计 100+ 条」的情形——51 轮 ReAct 里历史工具结果把消息
    上下文从 3.2k 推到 99.6k token，而且每轮都要重发一遍（单期 461 万输入
    token 的主要来源）。现在按**数量**保留最近 N 条工具结果，更早的一律压成
    预览；这是 tool-result clearing 的思路，不依赖时间戳（内部消息没有可靠
    时间戳），也不做 LLM 摘要。

    Args:
        messages: 消息列表
        tool_result_expiry_s: 工具结果过期秒数（仅在块带 ``_timestamp`` 时生效）
        keep_recent_tool_results: 原样保留的最近工具结果条数（最少 1）
        min_compact_chars: 超过该字符数的旧结果才压缩
        compact_preview_chars: 旧结果压缩后保留的开头字符数
        current_time: 当前时间（测试用）

    Returns:
        清理后的消息列表（原地修改）
    """
    now = current_time or time.time()
    cleaned = 0
    total_messages = len(messages)

    blocks: list[tuple[int, dict]] = []
    for index, msg in enumerate(messages):
        content = msg.get("content")
        if not isinstance(content, list):
            continue
        for block in content:
            if isinstance(block, dict) and block.get("type") == "tool_result":
                blocks.append((index, block))

    cutoff = max(0, len(blocks) - max(1, int(keep_recent_tool_results)))
    seen_cache_refs: set[str] = set()
    seen_tool_fingerprints: dict[str, int] = {}

    # Pass 1: 重复/缓存结果合并（对全部历史生效）。保留首次出现的副本，后续
    # 副本（含最近窗口内的）替换为短标记——内容完全相同，模型已经看过第一份。
    for _, block in blocks:
        result_content = block.get("content", "")
        if not isinstance(result_content, str) or not result_content:
            continue

        cache_key = str(block.get("cache_key", "") or "")
        if not cache_key and result_content.startswith("[系统缓存:"):
            cache_key = result_content.split("]", 1)[0]
        if cache_key:
            if cache_key in seen_cache_refs:
                block["content"] = f"[cached tool result duplicate merged: {cache_key}]"
                cleaned += 1
                continue
            seen_cache_refs.add(cache_key)

        tool_name = str(block.get("tool_name", "") or "")
        fingerprint = hashlib.md5(
            result_content[:4000].encode("utf-8", errors="ignore")
        ).hexdigest()[:12]
        semantic_key = f"{tool_name}:{fingerprint}"
        if seen_tool_fingerprints.get(semantic_key):
            block["content"] = (
                f"[tool result merged] 重复/相似的 {tool_name or 'tool'} 结果已合并；"
                f"保留首次出现的副本，历史副本 fingerprint={fingerprint}。"
            )
            cleaned += 1
            continue
        seen_tool_fingerprints[semantic_key] = 1

    # Pass 2: 只对「超出最近 N 条」的历史结果做过期清理与预览压缩。
    for order, (_, block) in enumerate(blocks):
        if order >= cutoff:
            continue
        result_content = block.get("content", "")
        if not isinstance(result_content, str) or not result_content:
            continue
        if result_content.startswith(
            ("[cached tool result duplicate merged", "[tool result merged]")
        ):
            continue

        timestamp = block.get("_timestamp", 0)
        if timestamp and (now - timestamp) > tool_result_expiry_s and len(result_content) > 100:
            block["content"] = "[expired tool result]"
            cleaned += 1
            continue

        if len(result_content) > min_compact_chars:
            block["content"] = (
                f"{result_content[:compact_preview_chars]}\n\n"
                f"...[旧工具结果已压缩：原始 {len(result_content)} 字符，"
                f"仅保留前 {compact_preview_chars} 字符；需要完整内容请重新调用该工具]"
            )
            cleaned += 1

    for index, msg in enumerate(messages):
        if index >= total_messages - 2:
            continue
        content = msg.get("content")
        if not isinstance(content, list):
            continue
        for block in content:
            if not isinstance(block, dict):
                continue
            if (
                block.get("type") in ("thinking", "redacted_thinking")
                and len(str(block.get("thinking", ""))) > 200
            ):
                block["thinking"] = "[thinking removed by microcompact]"
                cleaned += 1

    if cleaned > 0:
        logger.debug("microcompact: cleaned %d blocks in %d messages", cleaned, total_messages)
    return messages


def snip_old_segments(
    messages: list[dict],
    *,
    max_groups: int = 50,
    snip_count: int = 5,
) -> tuple[list[dict], int]:
    """直接丢弃最早的 N 组对话段（History Snip）。

    零 LLM 调用成本，适用于超长对话的快速上下文释放。
    通过 user/assistant 消息对分组，移除最早的 N 组。

    Args:
        messages: 消息列表
        max_groups: 当组数超过此值时触发裁剪
        snip_count: 每次裁剪的组数

    Returns:
        (裁剪后的消息列表, 被移除的消息数量)
    """
    groups = _group_messages(messages)
    if len(groups) <= max_groups:
        return messages, 0

    to_snip = min(snip_count, len(groups) - 1)  # Keep at least 1 group
    snipped_msgs = 0
    for i in range(to_snip):
        snipped_msgs += len(groups[i])

    boundary_marker = {
        "role": "user",
        "content": f"[HISTORY_SNIP: removed {snipped_msgs} messages from {to_snip} conversation turns]",
        "_internal": False,
    }

    remaining = [boundary_marker]
    for group in groups[to_snip:]:
        remaining.extend(group)

    logger.info(
        "history_snip: removed %d messages (%d groups), %d remaining",
        snipped_msgs,
        to_snip,
        len(remaining),
    )
    return remaining, snipped_msgs


def _group_messages(messages: list[dict]) -> list[list[dict]]:
    """将消息按 user→assistant 对话轮次分组。

    每组以 user 消息开始，包含紧随的 assistant 消息和相关 tool_result。
    """
    groups: list[list[dict]] = []
    current: list[dict] = []

    for msg in messages:
        role = msg.get("role", "")
        if role == "user" and current:
            groups.append(current)
            current = []
        current.append(msg)

    if current:
        groups.append(current)

    return groups

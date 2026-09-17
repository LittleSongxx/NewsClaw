"""Markdown → 飞书文档块（本地确定性转换，NewsClaw 自研）。

**为什么不用飞书的 markdown 转换接口**：``/docx/v1/documents/blocks/convert``
返回的 blocks 数组顺序与文档顺序**不一致**（实测同一份 59 块的稿子被打乱，
且不带任何可复原顺序的层级信息），直接批量插入会得到一篇乱序文档；它还对
用户身份额外要求 ``docx:document.block:convert`` 权限。本地转换按行解析、
顺序即文档顺序，行为可预测、可测试，也不依赖额外权限。

支持的语法（覆盖早报产物的实际用法）：
    标题 #..#######（1–9 级）、段落、- / * / + 无序列表、1. 有序列表、
    > 引用、``` 代码块、--- 分割线，
    以及行内 **加粗** / *斜体* / `行内代码` / [链接](url)。
"""

from __future__ import annotations

import re

# 飞书块类型号（docx v1）
_BT_TEXT = 2
_BT_HEADING_BASE = 2  # heading1 = 3 ... heading9 = 11
_BT_BULLET = 12
_BT_ORDERED = 13
_BT_CODE = 14
_BT_QUOTE = 15
_BT_DIVIDER = 22

_HEADING_RE = re.compile(r"^(#{1,9})\s+(.*)$")
_BULLET_RE = re.compile(r"^[-*+]\s+(.*)$")
_ORDERED_RE = re.compile(r"^\d+[.)]\s+(.*)$")
_QUOTE_RE = re.compile(r"^>\s?(.*)$")
_DIVIDER_RE = re.compile(r"^(-{3,}|\*{3,}|_{3,})$")
_FENCE_RE = re.compile(r"^```(.*)$")

# 行内样式：粗体 / 行内代码 / 链接 / 斜体
_INLINE_RE = re.compile(
    r"(\*\*(?P<bold>.+?)\*\*)"
    r"|(`(?P<code>[^`]+)`)"
    r"|(\[(?P<link_text>[^\]]+)\]\((?P<link_url>[^)]+)\))"
    r"|(?<!\*)\*(?P<italic>[^*]+)\*(?!\*)"
)


def _text_run(content: str, *, bold=False, italic=False, code=False, link: str = "") -> dict:
    style: dict = {}
    if bold:
        style["bold"] = True
    if italic:
        style["italic"] = True
    if code:
        style["inline_code"] = True
    if link:
        style["link"] = {"url": link}
    run: dict = {"content": content}
    if style:
        run["text_element_style"] = style
    return {"text_run": run}


def _inline_elements(text: str) -> list[dict]:
    """把一行文本解析成带样式的 elements（顺序与原文一致）。"""
    elements: list[dict] = []
    pos = 0
    for m in _INLINE_RE.finditer(text):
        if m.start() > pos:
            elements.append(_text_run(text[pos : m.start()]))
        if m.group("bold") is not None:
            elements.append(_text_run(m.group("bold"), bold=True))
        elif m.group("code") is not None:
            elements.append(_text_run(m.group("code"), code=True))
        elif m.group("link_text") is not None:
            elements.append(_text_run(m.group("link_text"), link=m.group("link_url")))
        elif m.group("italic") is not None:
            elements.append(_text_run(m.group("italic"), italic=True))
        pos = m.end()
    if pos < len(text):
        elements.append(_text_run(text[pos:]))
    if not elements:
        elements.append(_text_run(text))
    return elements


def _block(block_type: int, key: str, text: str = "") -> dict:
    if block_type == _BT_DIVIDER:
        return {"block_type": _BT_DIVIDER, "divider": {}}
    if block_type == _BT_CODE:
        # 代码块内容必须逐字保留：不能走行内解析（否则 ** 会被当成加粗标记吃掉）
        return {"block_type": _BT_CODE, "code": {"elements": [_text_run(text)]}}
    return {"block_type": block_type, key: {"elements": _inline_elements(text)}}


def markdown_to_blocks(markdown: str) -> list[dict]:
    """把 Markdown 转成飞书文档块列表，**顺序即文档顺序**。"""
    blocks: list[dict] = []
    lines = markdown.replace("\r\n", "\n").split("\n")
    in_code = False
    code_lines: list[str] = []

    for raw in lines:
        line = raw.rstrip()

        # 代码块：整块保留（内部不做行内解析）
        fence = _FENCE_RE.match(line)
        if fence:
            if in_code:
                blocks.append(_block(_BT_CODE, "code", "\n".join(code_lines)))
                code_lines, in_code = [], False
            else:
                in_code = True
            continue
        if in_code:
            code_lines.append(raw)
            continue

        stripped = line.strip()
        if not stripped:
            continue

        if _DIVIDER_RE.match(stripped):
            blocks.append(_block(_BT_DIVIDER, "divider"))
            continue

        heading = _HEADING_RE.match(stripped)
        if heading:
            level = min(len(heading.group(1)), 9)
            blocks.append(_block(_BT_HEADING_BASE + level, f"heading{level}", heading.group(2).strip()))
            continue

        quote = _QUOTE_RE.match(stripped)
        if quote:
            blocks.append(_block(_BT_QUOTE, "quote", quote.group(1).strip()))
            continue

        bullet = _BULLET_RE.match(stripped)
        if bullet:
            blocks.append(_block(_BT_BULLET, "bullet", bullet.group(1).strip()))
            continue

        ordered = _ORDERED_RE.match(stripped)
        if ordered:
            blocks.append(_block(_BT_ORDERED, "ordered", ordered.group(1).strip()))
            continue

        blocks.append(_block(_BT_TEXT, "text", stripped))

    if in_code and code_lines:  # 未闭合的代码块也要落地，避免内容丢失
        blocks.append(_block(_BT_CODE, "code", "\n".join(code_lines)))
    return blocks

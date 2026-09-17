"""飞书 Markdown→块 转换器单元测试。

背景：飞书自带的 ``blocks/convert`` 接口实测返回**乱序** blocks（同一份稿子
数组顺序与文档顺序不符且无法复原），直接插入会得到一篇乱序文档。本转换器
改为本地确定性实现，这里的断言锁定"顺序即文档顺序"这一核心契约。
"""

from __future__ import annotations

from newsclaw.integrations.feishu_markdown import markdown_to_blocks


def _text_of(block: dict) -> str:
    for key, value in block.items():
        if key == "block_type":
            continue
        if isinstance(value, dict) and "elements" in value:
            return "".join(e.get("text_run", {}).get("content", "") for e in value["elements"])
    return ""


def test_block_order_matches_document_order():
    md = """\
# 一级标题

第一段。

## 二级标题

- 项目一
- 项目二

> 引用内容

---

### 三级标题

1. 第一步
2. 第二步
"""
    blocks = markdown_to_blocks(md)
    kinds = [b["block_type"] for b in blocks]
    # 3=heading1, 2=text, 4=heading2, 12=bullet, 15=quote, 22=divider, 5=heading3, 13=ordered
    assert kinds == [3, 2, 4, 12, 12, 15, 22, 5, 13, 13]
    assert _text_of(blocks[0]) == "一级标题"
    assert _text_of(blocks[3]) == "项目一"


def test_inline_styles_are_parsed():
    md = "普通 **加粗** 与 `代码` 以及 [链接](https://example.com) 和 *斜体*。"
    blocks = markdown_to_blocks(md)
    elements = blocks[0]["text"]["elements"]
    styles = [e["text_run"].get("text_element_style", {}) for e in elements]
    assert any(s.get("bold") for s in styles)
    assert any(s.get("inline_code") for s in styles)
    assert any(s.get("italic") for s in styles)
    link = next(s for s in styles if s.get("link"))
    assert link["link"]["url"] == "https://example.com"
    # 拼接后的纯文本应与原文一致（样式不吞字）
    assert "".join(e["text_run"]["content"] for e in elements) == (
        "普通 加粗 与 代码 以及 链接 和 斜体。"
    )


def test_code_block_is_kept_verbatim_and_not_inline_parsed():
    md = "```\n**not bold**\nprint(1)\n```\n"
    blocks = markdown_to_blocks(md)
    assert len(blocks) == 1
    assert blocks[0]["block_type"] == 14
    assert _text_of(blocks[0]) == "**not bold**\nprint(1)"


def test_unclosed_code_fence_does_not_lose_content():
    blocks = markdown_to_blocks("```\n留在这里\n")
    assert len(blocks) == 1 and "留在这里" in _text_of(blocks[0])


def test_empty_input_yields_no_blocks():
    assert markdown_to_blocks("") == []
    assert markdown_to_blocks("\n\n   \n") == []

"""本地 Wiki 工具定义（NewsClaw 自研）。

把调研结果沉淀为本地 Markdown 知识库页面（主题页 / 公司页），结构由工具统一
维护：frontmatter、按日期的章节、要点与来源、"相关"互链、以及 MOC 索引页。
**同一天重复调用会替换该日期章节**，因此可安全重跑。
"""

WIKI_TOOLS: list[dict] = [
    {
        "name": "wiki_upsert",
        "category": "Knowledge",
        "description": (
            "Write or update one page of the local Markdown knowledge base (wiki). "
            "Creates topic/company atomic pages with date sections, source links, "
            "[[wikilinks]] and maintains the MOC index. Idempotent per date: calling "
            "twice for the same day replaces that day's section instead of duplicating it."
        ),
        "detail": (
            "把当天的调研要点沉淀进本地知识库（`data/wiki/`，同时也是一个标准 Obsidian 库）。\n"
            "- kind=topic：主题页（大模型 / 公司动态 / 开源项目 / 研究与论文 / 政策与监管）；\n"
            "- kind=company：重点公司页（本期反复出现的头部公司才建）；\n"
            "- entries 里每条给出 text（一句话要点）与 source（可点开的原始链接）；\n"
            "- links 填「相关页面名」（会自动写成 [[双链]]，供知识网络与反链使用）；\n"
            "- 返回写入的页面相对路径，把它记进 manifest.wiki_entries。\n"
            "注意：页面名不要带 / 与 ..；索引页 MOC.md 由工具自动维护，不要手写。"
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "page": {
                    "type": "string",
                    "description": "页面名，如「大模型」「OpenAI」（主题名或公司名）。",
                },
                "kind": {
                    "type": "string",
                    "enum": ["topic", "company"],
                    "description": "topic=主题页（默认），company=公司页。",
                },
                "day": {
                    "type": "string",
                    "description": "日期 YYYY-MM-DD（当天）；同一天重复写会替换该日章节。",
                },
                "summary": {
                    "type": "string",
                    "description": "当日该页的一句话概述（可选，将渲染为引用块）。",
                },
                "entries": {
                    "type": "array",
                    "description": "当日要点列表。",
                    "items": {
                        "type": "object",
                        "properties": {
                            "text": {"type": "string", "description": "一句话要点"},
                            "source": {"type": "string", "description": "原始来源链接"},
                        },
                        "required": ["text"],
                    },
                },
                "links": {
                    "type": "array",
                    "description": "相关页面名列表（生成 [[双链]]）。",
                    "items": {"type": "string"},
                },
            },
            "required": ["page", "day", "entries"],
        },
        "related_tools": [
            {"name": "feishu_doc", "relation": "对外分享归档（本工具是本地知识库）"},
        ],
    },
]

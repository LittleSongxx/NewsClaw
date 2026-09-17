"""Feishu 云文档工具定义（NewsClaw 自研）。

把每日早报产物归档到飞书云文档：创建文档 / 追加内容 / 可选挂到 Wiki 空间节点。
与上游技能市场无关，使用 .env 中的机器人凭证（FEISHU_APP_ID / FEISHU_APP_SECRET）。
"""

FEISHU_DOC_TOOLS: list[dict] = [
    {
        "name": "feishu_doc",
        "category": "IM Channel",
        "description": (
            "Create or append to a Feishu (Lark) cloud document. Content is passed as "
            "Markdown and converted to native doc blocks server-side. Optionally attach "
            "the new document as a node in a Wiki space for knowledge-base archival. "
            "Use this to archive deliverables; use deliver_artifacts to push files into a chat."
        ),
        "detail": (
            "把 Markdown 写入飞书云文档（docx）。与 deliver_artifacts（推聊天消息）互补：\n"
            "- action=create：新建文档并写入正文，可用 wiki_space_id 挂到 Wiki 空间节点；\n"
            "- action=append：向已有 document_id 追加正文（适合每天在同一份文档里追加一期）；\n"
            "返回 JSON 含 document_id 与可点开的 url，便于写进 manifest/wiki 索引。\n"
            "前置：.env 配置 FEISHU_APP_ID / FEISHU_APP_SECRET；Wiki 归档还需应用开通 "
            "wiki:wiki 权限且目标空间对该应用可见。"
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "markdown": {
                    "type": "string",
                    "description": "要写入的 Markdown 正文（支持标题/加粗/列表/引用/分割线）。",
                },
                "action": {
                    "type": "string",
                    "enum": ["create", "append"],
                    "description": "create=新建文档；append=向已有文档追加。默认 create。",
                },
                "title": {
                    "type": "string",
                    "description": "文档标题（action=create 时生效）。",
                },
                "document_id": {
                    "type": "string",
                    "description": "目标文档 ID（action=append 必填）。",
                },
                "wiki_space_id": {
                    "type": "string",
                    "description": (
                        "可选：新建文档后挂到该 Wiki 空间（需 wiki:wiki 权限）；"
                        "留空则仅创建在云空间。"
                    ),
                },
            },
            "required": ["markdown"],
        },
        "related_tools": [
            {"name": "deliver_artifacts", "relation": "推送文件到聊天窗口（本工具写云文档）"},
        ],
    },
]

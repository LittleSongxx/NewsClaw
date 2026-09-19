"""微信公众号发布工具定义（NewsClaw 自研）。

把每日早报的公众号稿（wechat.md）经公众号服务端 API 发布：
正文/封面素材上传 → 新建草稿 →（可选）立即发布。使用 .env 中的
WECHAT_MP_APP_ID / WECHAT_MP_APP_SECRET；与微信个人号通道（wechat）无关。
"""

WECHAT_MP_TOOLS: list[dict] = [
    {
        "name": "wechat_mp_publish",
        "category": "IM Channel",
        "description": (
            "Publish an article to a WeChat Official Account (mp.weixin.qq.com). "
            "Reads a Markdown article, converts it to inline-styled HTML, uploads body "
            "images and cover to the media library, creates a draft, and optionally "
            "submits it for publishing. Requires WECHAT_MP_APP_ID / WECHAT_MP_APP_SECRET."
        ),
        "detail": (
            "把 Markdown 稿发布到微信公众号（AppID/AppSecret 服务端 API）：\n"
            "- 默认从 content_path 读稿；「### 基础信息」小节解析出 标题/摘要/作者 进草稿字段，"
            "「封面图建议」小节不进正文；\n"
            "- 正文里的本地图/外链图自动上传为微信站内图（外链图在公众号内不显示，必须转存）；\n"
            "- 封面优先级：cover_path 参数 > 同目录 assets/ 图片 > 内置生成的品牌渐变封面；\n"
            "- publish=true（或配置 wechat_mp_auto_publish）时创建草稿后立即调发布接口，"
            "轮询拿到文章链接写入返回 receipt 的 url；false 仅存草稿箱，待人工确认；\n"
            "- 未 ready 的早报产物会被拒绝（与 deliver_artifacts 同一道投递门）。\n"
            "前置：.env 配置 WECHAT_MP_APP_ID / WECHAT_MP_APP_SECRET，且公众号具备"
            "草稿/发布接口权限（个人订阅号无权限，会返回 48001）；本机出口 IP 需加入"
            "公众号后台 IP 白名单。"
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "content_path": {
                    "type": "string",
                    "description": "要发布的 Markdown 稿绝对路径（如 issues/YYYY-MM-DD/wechat.md）。",
                },
                "content": {
                    "type": "string",
                    "description": "可选：直接传 Markdown 全文（与 content_path 二选一，path 优先）。",
                },
                "title": {
                    "type": "string",
                    "description": "可选：文章标题（≤64 字）；默认取「基础信息」的标题或首个一级标题。",
                },
                "digest": {
                    "type": "string",
                    "description": "可选：摘要（≤120 字）；默认取「基础信息」的摘要或正文开头。",
                },
                "author": {
                    "type": "string",
                    "description": "可选：作者署名；默认取配置 wechat_mp_author。",
                },
                "cover_path": {
                    "type": "string",
                    "description": "可选：封面图本地路径；默认扫描稿件同目录 assets/ 下的图片，再退化为内置生成封面。",
                },
                "thumb_media_id": {
                    "type": "string",
                    "description": "可选：已上传的永久封面素材 media_id（传入则跳过封面上传）。",
                },
                "publish": {
                    "type": "boolean",
                    "description": (
                        "可选：true=创建草稿后立即发布；false=仅存草稿箱。"
                        "缺省读配置 wechat_mp_auto_publish。"
                    ),
                },
            },
            "required": [],
        },
        "related_tools": [
            {"name": "feishu_doc", "relation": "同一份早报的飞书云文档归档出口"},
            {"name": "deliver_artifacts", "relation": "产物文件推送 owner 飞书私聊（审核通道）"},
        ],
    },
]

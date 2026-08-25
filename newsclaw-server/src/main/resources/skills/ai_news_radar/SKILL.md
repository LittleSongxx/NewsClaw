---
name: ai_news_radar
description: '全球与中国 AI 圈动态雷达：模型发布、具身智能、机器人、芯片、大厂 AI 产品、开源生态与行业合作。输出结构化事件和可追溯来源证据。'
version: 1.0.0
icon: '🛰️'
tags:
- AI
- 动态追踪
- 事实核验
- robotics
- models
dependencies:
  tools:
  - web_search
  - browser_use
  - ai_news_event
  - ai_news_review_card
---

# AI 动态雷达

本技能服务于「AI 动态内容运营」主线，不回答泛新闻问题。目标是把实时信息转换成可核验、可回放的事件证据包，供 Team Run 内容生产使用。

## 追踪范围

按以下分类检索，全球与中国来源并行：

1. 模型与研究：OpenAI、Anthropic、DeepSeek、Google DeepMind、Meta AI、Qwen、智谱等。
2. 具身智能与机器人：宇树科技、优必选、智元、傅利叶、Figure、Tesla、Boston Dynamics 等。
3. 芯片与基础设施：NVIDIA、AMD、昇腾、寒武纪、云厂商推理服务和数据中心。
4. 大厂 AI 产品：字节 Seed/火山、阿里、百度、腾讯、华为、小米等。
5. 开源与开发者生态：Hugging Face、GitHub Release、论文和官方项目页。
6. 融资、合作、政策与安全事件：必须明确事实、时间和来源等级。

来源白名单见 `references/source_registry.yml`。官方来源是事实主证据；媒体只负责发现线索或补充背景。

## 发现流程

1. 先调用 `ai_news_event(action=source_health)` 查看已配置来源；来源未配置或不可用时必须如实说明，不能虚构检索结果。
2. 优先调用 `ai_news_event(action=search_sources, query=..., providerIds=..., sourceLimit=..., language=...)` 取得带 `providerId`、`sourceTier`、canonical URL、抓取时间和 HTTP 状态的候选资料。该操作只读，不会自动入库、核验或发布。
3. 当结构化来源覆盖不足时，再使用 `web_search` 补充发现线索。每个分类分别查询，使用 `freshness=day` 或 `week`、`language=zh-CN`/`en`、`site:` 限定官方域名。
4. 对候选结果调用 `ai_news_event(action=fetch_source, providerId=..., sourceUrl=...)` 或打开页面并阅读实际正文；搜索摘要不能单独作为证据。
5. 标准化 URL（去 fragment、统一主机名和末尾斜杠），调用 `ai_news_event(action=upsert)` 写入候选事件。只有这一步会创建或更新事件；后端会基于事件、证据和抓取审计自动创建、更新或关闭持久化人工复核任务。
6. 每条证据必须带 `sourceUrl`、`claim`、来源等级和必要的原文摘录。不要写入无法打开或无法定位的链接。
7. 官方来源优先；没有官方来源时，至少收集来源注册表中的两个独立可信媒体。未注册媒体只能作为线索，不贡献 `verified` 门禁。来源冲突时保留双方 claims，并让事件进入 `conflicted`。
8. 传闻、预测和未经确认的爆料必须在摘要中标注「未证实」，不可改写成确定事实。
9. 官方页面返回 401/403 只表示抓取被阻断，404 只表示该 URL 不存在；两者都不能改写成「官方没有发布」。此时默认继续跟踪，不能建议直接进入内容生产。
10. `reviewRequired` 和复核任务状态只以后端确定性策略为准，不能用模型输出中的“建议人工复核”替代。当前请求来自飞书人工会话且需要通知操作者时，可调用 `ai_news_review_card`，直接传入 `ai_news_event` 返回的事件 ID；不得解析自然语言回复来猜事件 ID。

飞书卡片只是复核队列的通知和显式操作入口，不是队列事实来源。卡片发送失败时任务仍保持 `PENDING`，发送结果会写入审计字段；只有身份绑定的人工动作或受认证的复核 API 可以记录操作者与结论。证据、冲突或抓取状态变化后，后端会按策略版本与风险指纹重新打开已经处理过的任务。

结构化来源是配置驱动的 RSS、SearXNG 或官方 API 适配器，不是全网爬虫。`search_sources` 返回的 provenance 只表示检索路径和抓取时间，不是事实核验结论；仍需逐项建立 claim 与 quote，并通过事件状态门禁。

## 事件包要求

每个候选事件至少包含：标题、分类、实体、发现时间、canonical URL、事实声明和来源发布时间。建议使用如下结构：

```json
{
  "title": "事件标题",
  "category": "model|robotics|infrastructure|product|open_source|industry|policy",
  "entities": ["DeepSeek"],
  "claims": ["可被来源直接支持的事实"],
  "evidence": [
    {
      "sourceUrl": "https://example.com/official",
      "sourceTier": "official",
      "claim": "来源支持的事实",
      "quote": "必要的原文摘录",
      "confidence": 0.9
    }
  ]
}
```

不要在本技能中直接生成公众号全文或小红书文案。事件必须同时满足后端状态为 `verified` 且没有 `PENDING` 人工复核任务，才允许开始 Team Run；生产入口会重新计算当前风险，不能依赖旧卡片或旧页面状态绕过门禁。冲突、传闻、抓取阻断或仅有未注册媒体的事件禁止开始生产。核验及必要复核完成后，由 AI 动态编辑 Team Run 调用 `gzh_article`、`xhs_note`、`deai_humanize` 和包装工具完成生产，最终仍需人工审批。

## 定时运行

每日雷达只发现、去重和推送候选，不自动发表。定时任务应把候选摘要发送到飞书、企业微信或钉钉，用户确认后再启动 Team Run。

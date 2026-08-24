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

1. 优先使用 `web_search`，每个分类分别查询，使用 `freshness=day` 或 `week`、`language=zh-CN`/`en`、`site:` 限定官方域名。
2. 对候选结果打开页面并阅读实际正文；搜索摘要不能单独作为证据。
3. 标准化 URL（去 fragment、统一主机名和末尾斜杠），调用 `ai_news_event(action=upsert)` 写入候选事件。
4. 每条证据必须带 `sourceUrl`、`claim`、来源等级和必要的原文摘录。不要写入无法打开或无法定位的链接。
5. 官方来源优先；没有官方来源时，至少收集来源注册表中的两个独立可信媒体。未注册媒体只能作为线索，不贡献 `verified` 门禁。来源冲突时保留双方 claims，并让事件进入 `conflicted`。
6. 传闻、预测和未经确认的爆料必须在摘要中标注「未证实」，不可改写成确定事实。
7. 官方页面返回 401/403 只表示抓取被阻断，404 只表示该 URL 不存在；两者都不能改写成「官方没有发布」。此时默认继续跟踪，不能建议直接进入内容生产。
8. 候选事件写入完成后，在当前请求来自飞书人工会话时调用 `ai_news_review_card`，直接传入 `ai_news_event` 返回的事件 ID。不得解析自然语言回复来猜事件 ID。

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

不要在本技能中直接生成公众号全文或小红书文案。只有后端状态为 `verified` 的事件才展示「开始 Team Run」按钮；冲突、传闻、抓取阻断或仅有未注册媒体的事件禁止开始生产。核验通过后由 AI 动态编辑 Team Run 调用 `gzh_article`、`xhs_note`、`deai_humanize` 和包装工具完成生产，最终仍需人工审批。

## 定时运行

每日雷达只发现、去重和推送候选，不自动发表。定时任务应把候选摘要发送到飞书、企业微信或钉钉，用户确认后再启动 Team Run。

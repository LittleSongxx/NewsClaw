---
title: NewsClaw AI 动态内容运营
description: NewsClaw 的 AI 行业事件发现、证据核验、多 Agent 内容生产、飞书通知和小红书素材交付闭环。
---

# AI 动态内容运营

NewsClaw 追踪全球与中国 AI 圈的时效性事件：模型与研究、具身智能和机器人、
芯片与基础设施、大厂 AI 产品、开源发布、融资合作与政策动态。

它不是通用新闻问答。每个对外交付的选题都必须通过以下闭环：

```text
发现候选 -> 事件去重 -> 官方证据 -> claims 核验/冲突门禁
-> Wiki 证据归档 -> Team Run 并行生产 -> 合规与人工审批
-> 飞书通知 / 小红书素材包 -> 记忆与待审 Skill proposal
```

## 事件与证据

事件状态为：

```text
candidate -> researching -> verified | conflicted | rejected
                         -> in_production -> published | archived
```

- 官方页面是事实主证据；媒体报道主要用于发现线索或补充背景。
- 每个事件以 canonical URL 与 hash 去重，避免同一发布被多次生产。
- claim 保存 URL、来源等级、引用片段、发布时间、置信度和冲突原因。
- 缺少标题、canonical URL 或有效证据的事件不能标为 `verified`。
- 冲突未解决时事件保持 `conflicted`，不能进入对外交付。

新闻正文和完整引用属于事件域与 Wiki 证据层，不能直接写进长期 Memory。

## Team Run

用户在飞书或工作台确认选题后，系统创建一个持久化 `runId`。热点发现、事实
核查、内容编辑、视觉编辑与合规交付作为有依赖的任务 DAG 运行。独立任务可并行，
但必须等待 `blockedBy` 前置任务完成。

运行控制面包含条件抢占、lease、heartbeat、retry、review、cancel、stale recovery
和 SSE 运行投影。外部交付的副作用写入幂等账本，因此 IM 投递失败不会回滚事件和
证据入库。

## 飞书与定时雷达

「每日 AI 动态雷达」在 `Asia/Shanghai` 的每天 `08:00` 运行，负责发现候选、
初步去重与通知。它不会定时自动发布内容。

飞书使用 WebSocket 长连接接收请求和展示阶段性进度。主动推送需要一个启用的飞书
渠道和有效的最近会话目标；没有目标时，任务仍可完成，但投递会记录为失败或未投递。

## 小红书交付

小红书路径的产物为：

- 证据边界和合规扫描后的正文、标题、话题与卡片元数据；
- 3 到 18 张竖版渲染卡片的手机预览；
- 可下载的 ZIP 素材包；
- 内容日历、事件和 Team Run 的可追溯关联。

NewsClaw 不自动登录或发布真实小红书账号。运营人员在创作中心人工上传素材、复核
并点击发布；这既是当前平台边界，也是防止绕过验证码和平台风控的明确设计。

## Memory 与 Skill

长期 Memory 保存稳定偏好、编辑约束和历史反馈，要求来源指针并受 token 预算、
workspace 隔离、冲突版本和新闻正文拦截保护。

Reflection 与 Routine Mining 默认关闭。启用后它们只生成脱敏的待审 Skill proposal，
不会自动修改生产 Skill。审核后的晋升、绑定、归档和恢复都保留审计记录。

## 模型链

默认推荐百炼 `qwen3.7-plus` 为主模型，`deepseek-v4-flash` 为备用模型。两者
通过 `.env` 的 `MATECLAW_PRIMARY_*` 和 `MATECLAW_FALLBACK_MODEL_CHAIN`
配置。供应商故障时可按健康状态、冷却与重试策略切换，并留下路由轨迹。

## 可复现实证

运行 `./scripts/eval-ai-news-ops.sh` 验证固定的核验策略、canonical URL、
Memory 写入门禁、Skill proposal、Cron 幂等、外部副作用账本和模型路由回归。

该脚本是离线确定性回归，不代表线上新闻发现准确率或真实平台投递成功率。线上
指标必须从事件、证据页、Team Run、审批与交付账本的真实记录统计。

# AI 新闻受控在线基线归档（2026-08-25）

本文冻结 NewsClaw AI 新闻闭环在 P0 结构化输出和确定性人工复核改造**之前**的优化过程与两轮真实调用结果。它是后续改造的可复核对照，不会被新协议的结果覆盖或重写。

## 证据边界

- 评测集：`controlled-live-ai-news-agent-evidence@2026-08-25-v1`，30 条冻结合成证据包。
- 调用链：真实本地 NewsClaw Web SSE 接口、已登录会话、真实模型路由、真实只读 `ai_news_event(action=source_health)` 工具路径。
- 标注来源：`frozen synthetic evidence-policy labels; not human ratings of production user traffic`。
- 不可据此声明：开放网络发现准确率、真实用户任务成功率、幻觉率、生产 QPS/SLA、容量、成本或用户满意度。
- 原始 SSE 未入库，保留在下方对应的 `target/` 目录；每条原始流及最终输出的 SHA-256 已写入 runtime manifest。这样保留可审计性，同时不提交运行时数据、JWT 或凭证。

## 优化与测评时间线

| 提交 | 阶段 | 保留的事实 |
| --- | --- | --- |
| `831bc03d` | 可审计 AI 新闻运营闭环 | 事件/证据台账、来源分级、核验状态机、内容引用边界、飞书复核卡、离线策略回归。 |
| `82b9e5b0` | 受控在线 Agent 评测 | 30 条冻结用例驱动真实 SSE/模型/只读工具，产生质量、运行时、原始流哈希和 badcase 工件。 |
| `82b9e5b0` 首轮运行 | 暴露真实缺口 | 仅靠 Prompt 约束的 JSON 输出和模型自报的 `humanReviewRequested` 不稳定。 |
| `85f5b9ca` | 证据口径校正 | 明确合成金标不是生产流量人工评分，避免将受控协议分数误表述为真实业务质量。 |
| `85f5b9ca` 重建后运行 | 正式对照基线 | 从干净源码树重建服务后重跑，确认 JSON 契约与 HITL 路由缺口仍真实存在，成为本轮 P0 改造的对照。 |

## 离线策略回归

| 项目 | 值 |
| --- | --- |
| 数据集 | `ai-news-evidence-policy@2026.08.25-v1` |
| 样本数 | 30 |
| badcase | 0 |
| 核验 F1 | 1.0000 |
| 拒绝 F1 | 1.0000 |
| Manifest | `target/ai-news-policy-quality-manifest.json` |
| SHA-256 | `9705971f31ff52d09d1607f6a6ce5d8a3b01ce73588d193aa51be37e3c307a2e` |

这是确定性策略回归门禁，不能解释为模型或线上发现准确率。

## 首轮受控在线结果

| 项目 | 值 |
| --- | --- |
| Git / 源码树 | `82b9e5b0` / clean |
| 目录 | `target/ai-news-live-agent-evaluation/20260825T051334Z/` |
| 模型路由 | `bailian-team::qwen3.7-plus` |
| 用例 / HTTP 200 / 流完成 | 30 / 100% / 100% |
| 严格 JSON 有效 | 16/30（53.33%） |
| 严格受控协议任务成功 | 10/30（33.33%） |
| 来源分级准确率 | 1.0000 |
| 核验 F1 / 拒绝 F1 / 引用边界 F1 | 1.0000 / 1.0000 / 1.0000 |
| Claim-Quote 冻结标签 F1 / HITL F1 | 0.9500 / 0.6400 |
| 工具选择 / 参数 / 执行 | 100% / 6/6（100%） / 6/6（100%） |
| E2E P50/P95 | 13.262s / 38.095s |
| TTFT P50/P95 | 12.486s / 36.944s |
| Prompt / Completion / Reasoning tokens | 721,347 / 26,485 / 24,405 |
| Quality manifest SHA-256 | `8dcf8713e0864e548cc3d58846dd770d8e2d0ab835bc1483e456fd1d9b233119` |
| Runtime manifest SHA-256 | `21e3a48c5b722e3475caf7770831eb492a45d5a693f9bab94174c4a164d33226` |

## 正式重建后对照基线

| 项目 | 值 |
| --- | --- |
| Git / 源码树 | `85f5b9ca` / clean |
| 目录 | `target/ai-news-live-agent-evaluation/20260825T053908Z/` |
| Run ID | `live-20260825T053912Z-db5db397` |
| 模型路由 | `bailian-team::qwen3.7-plus` |
| 用例 / 原始 SSE / HTTP 200 / 流完成 | 30 / 30 / 100% / 100% |
| 严格 JSON 有效 | 13/30（43.33%） |
| 严格受控协议任务成功 | 10/30（33.33%） |
| 来源分级准确率 | 1.0000 |
| 核验 F1 / 拒绝 F1 / 引用边界 F1 | 0.9677 / 0.9655 / 0.9714 |
| Claim-Quote 冻结标签 F1 / HITL F1 | 0.9231 / 0.6923 |
| 工具选择 / 参数 / 执行 | 100% / 6/6（100%） / 6/6（100%） |
| E2E P50/P95 | 13.921s / 25.773s |
| TTFT P50/P95 | 12.723s / 24.524s |
| Tool P50/P95 | 1ms / 5ms |
| Prompt / Completion / Reasoning tokens | 720,632 / 23,854 / 21,654 |
| 质量层 badcase | 34 |
| `source_health` unknown action / 工具错误 | 0 / 0 |
| Quality manifest SHA-256 | `c028d700622ec6aaf09c797b647778af5178f0b3a532d0654f82a54196003e19` |
| Runtime manifest SHA-256 | `2fb86ae8e67984b608a864293352f9ce43ac8cab7f03057a96c44b0ea80c9d12` |

## 关键 badcase 根因与改造目标

正式基线的失败归类如下：

| 根因 | 数量 | P0 对策 |
| --- | ---: | --- |
| 最终答案不是无 Markdown fence 的单一 JSON 对象 | 17 | Web API 的 `json_object` 明确契约、OpenAI 兼容模型原生 `response_format`、服务端严格校验和 SSE 状态事件。 |
| 模型决策字段未匹配冻结标签 | 11 | 保留为 Prompt/模型/人工标注 trace 的后续质量优化对象，不以字符串清洗伪造成功。 |
| 引用 ID 或引用决策违反任务契约 | 3 | 保留事件证据边界门禁，并在后续真实 trace 样本中人工复核。 |

`humanReviewRequested` 的 F1 为 0.6923，说明让模型自行声明是否需要人工处理不够可靠。本轮 P0 将把冲突、核验未满足、低可信来源、缺失 claim/quote、未捕获官方证据等条件转为后端确定性复核队列与生产前门禁；模型字段只作为解释性输出，不再是路由真相来源。

## 后续可复核规则

1. 新协议使用新的数据集/协议版本和新的输出目录，绝不篡改 `live-agent-evidence-v1` 的工件或基线数字。
2. 每次正式运行同时记录 Git SHA、`evaluationTree`、模型路由、样本数、badcase、P50/P95 和 manifest SHA-256。
3. 真实业务质量需要脱敏的抽样 trace、人工标注、采样窗口、模型/Prompt/Skill 版本和复核协议；可使用 `scripts/score-ai-news-traces.sh` 评分，但不得把本文的合成结果替代为人工证据。

# AI 新闻质量证据索引

本目录只归档能够支持 NewsClaw 项目主张的质量证据、优化过程和解释边界。编译成功、代码测试通过等属于变更合入的基本条件，不作为质量指标或优化成果写入本索引。

## 当前可引用结论

正式 v3 结果来自干净的 `7ed1602b` 源码树、30 条冻结合成证据包、真实 NewsClaw Web SSE 和 `bailian-team::qwen3.7-plus` 路由：

| 维度 | 结果 | 解释边界 |
| --- | ---: | --- |
| 严格受控任务成功 | 26/30（86.67%） | 固定合成证据协议，不是真实用户任务成功率 |
| 来源等级准确率 / Macro-F1 | 96.67% / 0.9328 | 仍有来源等级与引文支持度耦合错误 |
| 核验准入 F1 | 0.9677 | 不代表开放网络事实核验准确率 |
| 正确拒绝 F1 | 0.9655 | 一次越界引用场景发生不必要拒绝 |
| 引用违规拦截 F1 | 1.0000 | 仅覆盖本数据集中的引用边界样本 |
| Claim-Quote 支持 F1 | 0.9524 | 仿冒来源和冲突样本仍有语义错误 |
| HTTP / SSE / 严格 JSON / 双解析契约 | 30/30 | 证明本次真实调用链的协议稳定性 |
| 禁止工具场景零调用 | 24/24 | 调用方声明 `toolChoice=none` |
| 必需只读工具成功 | 6/6 | 强制编排与执行器证据，不是自主选工具准确率 |
| E2E P50 / P95 | 10.149s / 17.295s | 单次顺序运行，不是生产 SLA |
| TTFT P50 / P95 | 9.152s / 16.539s | 主要耗时来自模型首字延迟 |
| 平均 Prompt tokens | 约 13.6k/条 | 可作为后续上下文压缩的基线 |

## 阅读顺序

| 文档 | 用途 |
| --- | --- |
| [P0 前受控在线基线](ai-news-controlled-live-baseline-20260825.md) | 保留 v1 基线、重建后对照、缺口和最初优化目标 |
| [P0/P1 后 v3 证据归档](ai-news-controlled-live-v3-20260825.md) | 当前正式结果、Provider 根因、两阶段工具协议、badcase 和哈希 |
| [v3 机器可读快照](ai-news-controlled-live-v3-20260825.json) | 供脚本或审阅者核对数据集、指标、运行身份和工件摘要 |
| [受控在线评测方法](../ai-news-live-agent-evaluation.md) | 运行方式、输出格式、评分规则和工具指标口径 |
| [质量评测分层](../ai-news-quality-evaluation.md) | 离线策略、受控在线和人工标注真实 trace 三层证据边界 |
| [运营闭环评测](../ai-news-ops-evaluation.md) | 来源、事件状态机、引用、交付和审计闭环的确定性评测入口 |

## 优化过程

| 提交 | 阶段性变化 | 解决的问题 |
| --- | --- | --- |
| `831bc03d` | 建立可审计 AI 新闻运营闭环 | 事件、证据、来源、核验、引用、复核和发布缺少统一状态链 |
| `82b9e5b0` | 引入真实 SSE/模型/只读工具受控评测 | 项目只有实现描述，没有真实调用链和 badcase 证据 |
| `85f5b9ca` | 校正证据口径 | 防止把冻结合成标签外推成生产准确率或用户效果 |
| `788d0555` | 强制结构化输出和确定性复核门禁 | Markdown/非 JSON 终态不稳定，模型自报复核不能作为路由真相 |
| `7ed1602b` | 显式工具编排和 Provider 兼容 | Qwen thinking 模式拒绝强制工具，同请求组合函数与 JSON 可能跳过工具 |
| `156ed4ee` | 冻结 v3 指标、badcase 和哈希 | 将优化结果从本地 `target/` 提炼成可审阅、可追溯的仓库证据 |

Provider 调查最终形成两阶段 Agent turn：第一阶段只强制一次已授权的只读工具调用，工具返回后切换为 `toolChoice=none`，第二阶段再生成严格 JSON 终态。显式工具选择不会扩大 Agent 工具面，也不会绕过 Tool Guard、审批、参数校验或执行器作用域。

## Badcase 结论

v3 的 9 条 badcase 记录来自 4 个失败样本：

- 仿冒域名的字面蕴含被误当成可信 Claim-Quote 支持。
- 模型按最贴近主张的引文选择来源等级，而不是取证据包中的最强来源。
- 越界引用请求污染了证据本身是否可核验的独立判断。
- 未解决冲突污染了单条可信引文是否直接支持主张的判断。

这些错误没有通过改金标、字符串清洗或服务端伪造字段消除。生产闭环中的引用准入、冲突、复核任务和发布门禁仍由确定性后端策略重新计算；模型输出保留为解释和质量观测。

## 工件保留规则

| 类型 | 位置 | 处理方式 |
| --- | --- | --- |
| 数据集、runner、评分器、脱敏摘要 | Git 跟踪路径 | 提交并推送 |
| 正式原始 SSE、完整 manifest、Provider 请求/响应 | `target/ai-news-live-agent-evaluation/` | 本地保留，Git 忽略；以 SHA-256 与仓库摘要关联 |
| API 密钥、密码、JWT、登录响应 | 不应出现在任何工件中 | 不采集、不归档、不提交 |
| smoke、diagnostics、失败的早期探针 | `target/` 临时目录 | 正式结果归档后删除 |
| Maven `target/`、前端 `node_modules/`、`dist/` | 各模块构建目录 | 可再生成，不作为证据，定期删除 |

当前正式本地证据仅保留以下批次：

```text
target/ai-news-live-agent-evaluation/20260825T051334Z/
target/ai-news-live-agent-evaluation/20260825T053908Z/
target/ai-news-live-agent-evaluation/v2-full-20260825T073324Z/
target/ai-news-live-agent-evaluation/provider-toolchoice-matrix-20260825T090600Z/
target/ai-news-live-agent-evaluation/provider-toolchoice-thinking-off-20260825T090656Z/
target/ai-news-live-agent-evaluation/direct-toolchoice-probe-20260825T091327Z/
target/ai-news-live-agent-evaluation/20260825T092721Z/
```

## 下一证据阶段

当前最需要增强的是证据代表性，而不是继续调整 Prompt 追求合成集满分：

1. 对同一版本执行多次重复运行，报告均值、最差值、波动和逐样本翻转率。
2. 建立真实公开事件的脱敏双人标注集，记录采样窗口、一致率和争议仲裁。
3. 对比裸模型建议与确定性门禁最终结果，测量高风险逃逸率、合法内容误拦率和复核闭环有效性。
4. 单独使用 `toolChoice=auto` 评估自主工具选择、参数、无效调用和失败恢复。
5. 拆解系统 Prompt、Skill、工具 Schema、历史和证据包 token 占比，再做上下文压缩与 TTFT 配对评测。

任何新结果都必须记录 Git SHA、数据集/Prompt/模型版本、采样方式、原始工件哈希和适用边界，不覆盖本目录已有历史数字。

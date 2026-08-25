# AI 动态质量评测

## 目标与边界

项目刻意把三类评测分开：

1. `scripts/eval-ai-news-quality.sh` 运行版本化、离线的证据策略基准，验证来源分级、核验准入、拒绝、引用边界和 canonical URL 去重没有回归。
2. `scripts/run-ai-news-live-agent-eval.sh` 使用冻结的合成证据包驱动真实 SSE、真实模型路由和只读工具调用，验证 Agent 的端到端结构化决策、工具纪律和一次运行的性能观测。
3. `scripts/score-ai-news-traces.sh` 评分人工标注的抽样 Agent trace，用于模型、Prompt、路由与真实业务质量的证据沉淀。

第一类无需凭证和网络，结果只能证明确定性策略正确性，不能表述成线上发现准确率、模型幻觉率或内容质量。第二类是受控在线基准，不使用真实用户数据，也不执行写入或发布动作；它可以证明真实 Agent 调用链上的固定证据决策、工具选择和本次运行时延，不能表述成开放网络发现准确率、QPS 或 SLA。第三类只有在数据集记录了明确的采样窗口、模型/Prompt/Skill 版本、复核协议和原始 trace 留存位置后，才可作为真实业务质量证据。

受控在线基准的执行、工件、指标和简历边界见 [受控在线 Agent 评测](ai-news-live-agent-evaluation.md)。

## 离线策略基准

执行：

```bash
./scripts/eval-ai-news-quality.sh
```

命令会在 `target/` 生成两份不入库的工件：

```text
target/ai-news-policy-quality-manifest.json
target/ai-news-policy-quality-report.md
```

当前语料为 [quality-policy-v1.json](../../newsclaw-server/src/test/resources/evals/ai-news/quality-policy-v1.json)，包含 30 个场景：注册官方源、两个独立媒体、同一媒体转载、未注册或伪官方域名、未解决冲突、证据不足、引用越界和 URL 变体。

每份报告都包含数据集版本、Git SHA、`evaluationTree=clean|dirty`、执行元数据、样本量、指标、切片、badcase 和限制说明。`evaluationTree` 检查参与评测的 README、后端、脚本和文档路径，因此不会被根目录下不参与评测的个人材料误伤。离线基准出现不匹配会使 JUnit 失败，因此它同时是测量工件和回归门禁。只有 `evaluationTree=clean` 的工件可以作为某次提交的正式基线；核验准入分数直接执行 `AiNewsEventService.verify` 的真实状态机路径，而不是维护一套平行的判定实现。

## 指标口径

报告保留单项指标，不使用一个加权总分掩盖安全回归。

| 指标 | 正类或分母 | 为什么要测 |
| --- | --- | --- |
| `sourceTier.accuracy`、`sourceTier.macroF1` | official / media / community 来源标签 | 来源可信度是所有下游动作的第一道准入边界。 |
| `verificationEligible` P/R/F1 | 事件可进入 `verified` | 衡量官方优先或独立媒体交叉验证的准入正确性。 |
| `properRefusal` P/R/F1 | 事件应被阻断或拒绝 | 捕获证据不足或不安全时的错误放行。 |
| `citationViolationBlocked` P/R/F1 | 未归档引用被阻断 | 防止内容生产臆造事实来源。 |
| `canonicalDedup.pairwise` P/R/F1 | 两个候选属于同一规范事件 | 同时衡量重复抑制和错误合并。 |
| `unresolvedConflict.blockRate` | 存在未解决冲突的样本 | 验证冲突不会静默通过核验。 |
| `claimQuoteSupported` P/R/F1 | claim 被引用 quote 支持 | 受控在线层只衡量与冻结合成金标的一致性；真实 claim 支持度只能由人工标注 trace 评分。 |
| `taskSuccess` P/R/F1 | 任务结果成功 | 受控在线层是严格协议成功，真实业务层才是人工裁定任务成功；当金标要求拒绝时，正确拒绝同样是成功。 |
| `toolSelectionCorrect` P/R/F1 | 工具集合和执行顺序正确 | 受控在线层仅覆盖冻结的只读工具契约；真实工具正确性需由抽样 trace 复核。 |
| `toolParametersCorrect` P/R/F1 | 工具参数符合 schema 与业务边界 | 受控在线层检查冻结参数契约，真实业务场景需保留复核证据。 |
| `humanReviewRouting` P/R/F1 | 需要人工时正确路由到人工复核 | 衡量 HITL 路由，不把所有自动化尝试都视作成功。 |

二分类指标的 JSON 工件会记录 `truePositive`、`falsePositive`、`falseNegative`、`trueNegative`、准确率（`value`）、Precision、Recall 和 F1。`n/a` 表示该数据集没有该指标的标注，不会用虚假的满分补齐。

## 真实 Agent Trace 评分

以 [ai-news-labeled-traces.example.json](examples/ai-news-labeled-traces.example.json) 为格式起点。该文件只是 schema 示例，不能被当作评测结果。

对每个抽样事件，复核者应私下保留不可变的来源快照，并标注：

- 来源分级，以及候选是否实际满足核验准入；
- 所有事实引用是否都来自已归档的 Evidence Packet；
- 每条关键 claim 是否被 quote 支持；
- 规范事件标识、应否拒绝、未解决冲突、任务结果、工具选择、工具参数和人工复核路由。

对有争议的事实或蕴含关系使用两位独立复核者，并记录最终裁决；在 `executionMetadata` 写入复核协议。原始 Prompt、来源快照、用户/渠道标识、API Key 和可能受授权限制的网页副本不要进入 Git。应在私有证据库中保留它们的 hash 或受控引用。

对一个已经标注的数据集执行：

```bash
./scripts/score-ai-news-traces.sh /absolute/path/to/labeled-traces.json
```

可选的第二个参数指定输出目录。脚本只读取输入并生成 JSON Manifest 与 Markdown 报告，不会修改数据集。模型或 Prompt 的 A/B 比较必须使用同一个冻结数据集和相同切片定义，并同时报告样本量、badcase 和聚合指标。

## 面试安全表述

离线结果可以支持以下表述：

> 构建版本化的 30 样例 Evidence Policy 回归基准，输出来源分级准确率、核验/拒绝/引用 P-R-F1、事件去重 Pairwise F1、场景切片及 JSON/Markdown badcase 工件；全程不依赖模型凭证和外部网络，可在 CI 与本地复跑。

不要将策略基准分数包装为线上模型准确率。完成真实 trace 标注后，只有同时提供采样窗口、事件数量、模型/Prompt/路由版本、人工复核协议和精确分数，才可以在简历中写发现准确率、引用质量或幻觉率。

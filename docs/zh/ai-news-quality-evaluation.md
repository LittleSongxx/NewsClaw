# AI 动态质量评测

## 目标与边界

项目刻意把五类评测分开：

1. `scripts/eval-ai-news-quality.sh` 运行版本化、离线的证据策略基准，验证来源分级、核验准入、拒绝、引用边界和 canonical URL 去重没有回归。
2. `scripts/run-ai-news-live-agent-eval.sh` 使用冻结的合成证据包驱动真实 SSE、真实模型路由和 provider 约束的只读工具调用，验证 Agent 的端到端结构化决策、执行器纪律和一次运行的性能观测。
3. `scripts/score-ai-news-traces.sh` 评分人工标注的抽样 Agent trace，用于模型、Prompt、路由与真实业务质量的证据沉淀。
4. `scripts/eval-ai-news-discovery-quality.sh` 对冻结时间窗口的人工事件账本和真实系统输出评分，测事件召回、有效精确率、发现时延、B³ 聚类、证据引用和 nDCG 排序；这是“AI 圈最新新闻搜集”功能主线的垂类评测入口。
5. `scripts/eval-ai-news-capture-funnel.sh` 遍历冻结发现清单的全部 URL，测完整抓取、正文抽取、快照回读、持久化 capture-bound 精确绑定、页面/受治理结构化来源时间和窗口准入，并保留失败分层；它定位候选到证据之间的第二层损耗。

第一类无需凭证和网络，结果只能证明确定性策略正确性，不能表述成线上发现准确率、模型幻觉率或内容质量。第二类是受控在线基准，不使用真实用户数据，也不执行写入或发布动作；v3 可以证明真实 Agent 调用链上的固定证据决策、声明式工具编排、执行器行为和本次运行时延，但其工具分数不能表述成模型自主选择准确率，也不能表述成开放网络发现准确率、QPS 或 SLA。第三类只有在数据集记录了明确的采样窗口、模型/Prompt/Skill 版本、复核协议和原始 trace 留存位置后，才可作为真实业务质量证据。第四类只有在来源宇宙、无未来数据回放、观察尾窗、人工事件匹配和排序快照均完整时，才能形成新闻搜集业务分数；算术 fixture 不能代替真实结果。第五类必须以全部冻结候选为分母；它的 exact probe 只证明机械引用合同，不证明 claim 语义支持，单窗口 HTTP 结果也不是生产成功率或 SLA。

垂类 P0 的公式、JSON 契约、标注口径和运行方法见 [AI 圈最新新闻搜集 P0 评测](ai-news-discovery-quality-evaluation.md)。受控在线基准的执行、工件、指标和简历边界见 [受控在线 Agent 评测](ai-news-live-agent-evaluation.md)，第二层冻结漏斗结果见[抓取、正文抽取与证据绑定证据](evidence/ai-news-capture-extraction-funnel-v1-20260828.md)，审批后 publisher 时间补证合同见[结构化时间证言证据](evidence/ai-news-structured-time-attestation-v1-20260828.md)，现有结果、优化提交链和工件保留规则统一收录在[质量证据索引](evidence/README.md)。

2026-08-25 对评分代码、冻结数据、原始 SSE 和历史报告做了独立审计。发现、修复、联网依据和仍未关闭的证据缺口见[评测体系审计与优化记录](evidence/ai-news-evaluation-audit-20260825.md)。历史 v3 工件保持冻结；新 schema 不回写旧 manifest。

P0 改造前的两轮受控在线基线及其 SHA-256 摘要见[基线归档](evidence/ai-news-controlled-live-baseline-20260825.md)；改造后 30 条完整 v3 结果、Provider 调查、badcase 和哈希见[v3 证据归档](evidence/ai-news-controlled-live-v3-20260825.md)。其中的冻结合成标签都不会被表述为生产用户流量的人类评分。

后续候选数据集扩展为 100 条，详见[100 条候选集](evidence/ai-news-holdout-100-20260826.md)。它已完成首次在线运行并被后续 Prompt/Agent 优化反复使用，因此当前是 development 回归集，不再是 unseen holdout；首次 84/100 及有效性复盘见[首轮结果与复盘](evidence/ai-news-holdout-100-first-look-20260826.md)。

冻结 v8 系统后创建的第二份 `sealed holdout v2` 包含 100 个不同语义场景、没有中英翻译对复用；唯一一次 candidate first-look 严格成功 89/100。它改善了旧集的场景独立性与运行身份记录，但仍是 AI 合成、机械裁决且未独立人工复核的数据，只能证明受控证据协议的候选泛化结果。预注册、结果和退役边界见[sealed v2 证据归档](evidence/ai-news-sealed-holdout-v2-20260826.md)。

首看 badcase 后的优化不回写 sealed v2：新生产合同把模型输出收敛为逐 Evidence 的 `semanticRelation + confidence`，由 `AiNewsDecisionPolicy` 使用 URL 注册表、独立 publisher、高风险、可信反证和 citation allowlist 确定性聚合。对应 30 条关系集与 30 条 `toolChoice=auto` 集都标为 development；前者覆盖已知语义失败，后者单独测自主只读工具选择。它们能做回归和稳定性分析，不能产生新的 unseen 分数。

## P0 契约与人工复核闭环证据

受控在线 v3 默认请求 `responseFormat=json_object`、`responseSchema=ai_news_decision_v1`，并用 `toolChoice=none|function:ai_news_event` 声明受控工具编排，同时保留 `stream_started` 的格式/schema/工具确认、`structured_output` 的服务端严格校验和 runner 独立严格解析结果。runner 进一步拒绝 Markdown fence、数组、前后缀、trailing token、重复 key、额外字段、枚举大小写漂移和引用 ID 的空白归一化；引用列表必须逐项等于协议要求，不能用集合去重后放过重复或额外 ID。不支持 JSON Object 原生约束的 Agent 路径在工具执行前显式失败，默认 `text/auto` 客户端保持兼容。在线结果只能说明这次真实模型/SSE 路径遵守了固定输出和声明式编排协议。

人工复核闭环不依赖模型自报。`AiNewsReviewPolicy` 只读取事件、证据和抓取审计，通过策略版本与风险指纹创建、保持、关闭或重新打开持久化任务；`AiNewsReviewRoutingService` 要求显式操作者和复核结论，并在开始生产前重新计算风险。飞书卡片只是通知 transport，发送失败不会删除待办，Agent 的核验动作也不能自动清空待办。

每条 Evidence 现在持久化 `semanticRelation`、`relationConfidence`、origin、reviewer、reviewedAt 和 note。省略/未知关系 fail-closed；人工关系复核必须绑定认证操作者，内容或关系改变会撤销旧核验。模型只负责 quote→完整 claim 的语义关系，来源等级、核验资格、冲突阻断和允许引用 ID 不再由模型自由输出。

这部分证据分别由以下测试承担：

| 证据 | 聚焦测试 |
| --- | --- |
| JSON Object 解析、SSE 事件和模型请求选项 | `StructuredOutputFormatTest`、`StructuredOutputContractTest`、`ReasoningNodeStructuredOutputTest`、`AiNewsLiveAgentBenchmarkRunnerTest` |
| 风险原因、指纹稳定性、解决后保持及风险变化重开 | `AiNewsReviewPolicyTest`、`AiNewsReviewRoutingServiceTest` |
| `verified` 之后仍受复核任务约束的生产门禁 | `AiNewsEventServiceTest` |
| 身份绑定卡片操作和发送审计 | `AiNewsReviewCardHandlerTest`、`AiNewsReviewCardToolTest`、`FeishuCardDispatcherTest` |
| H2 空库迁移到复核队列表 | `AiNewsMigrationSmokeTest` |

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
| `canonicalDedup.pairwise` P/R/F1 | 两个候选属于同一规范事件 | 同时衡量重复抑制和错误合并；pair 共享原始 case、并非独立试验，因此不输出 Wilson 区间。 |
| `unresolvedConflict.blockRate` | 存在未解决冲突的样本 | 验证冲突不会静默通过核验。 |
| `claimQuoteSupported` P/R/F1 | 可信 Evidence Packet 是否支持 claim | v3 金标把来源可信度纳入支持判定，因此它是 trust-aware evidence support，不是纯文本蕴含；真实支持度只能由人工标注 trace 评分。 |
| `semanticRelationItemAccuracy` | v2 每条 evidence 的五分类关系 | development v9/v10 才有；先逐项评分，再由生产策略聚合。它不是开放网页真实性评分。 |
| `taskSuccess` 通过率 | 任务结果成功 | 受控在线层是严格协议成功，真实业务层才是人工裁定任务成功；v3 金标全为成功，主结论应使用 `value` 通过率而不是全正类 F1。 |
| `toolSelectionCorrect` 通过率 | 工具集合和执行顺序正确 | v3 金标全为正确且由调用方声明工具，只能证明强制编排与执行器结果，不是模型自主选择分数。 |
| `toolParametersCorrect` 通过率 | 工具参数符合 schema 与业务边界 | v3 只有 6 个必需工具样本且金标全为正确，必须同时展示样本量、Wilson 区间和小样本警告。 |
| `humanReviewRouting` P/R/F1 | 需要人工时给出正确的模型解释性决策 | 受控在线层不等同于持久化后端路由；真实任务创建、重开、解决和生产门禁由确定性策略测试单独证明。 |

二分类指标的 JSON 工件会记录 `truePositive`、`falsePositive`、`falseNegative`、`trueNegative`、准确率/通过率（`value`）、Wilson 95% 区间、Precision、Recall、F1、`invalidPredictions` 和覆盖警告。`N<20`、金标只有一个类别或预测字段缺失都会显式告警；单类任务以 `value` 为主，不用无信息量的 F1 包装结果。`n/a` 表示没有该指标的标注，不会用虚假的满分补齐。

`properRefusal` 直接评分预测中的 `refusalIssued`，不再从 `!verificationEligible` 推导。每条预测还必须显式声明 `outputValid`：只有 `outputValid=false` 才允许字段缺失，缺失字段仍计入分母并按错误处理；已存在字段继续独立评分。人工 trace 若既缺字段又未声明输出无效，会被拒绝而不是默认为 `false`。

旧的 `scripts/eval-ai-news-evidence.sh` 只生成早期 fixture pass-rate 工件。其指标已按实际分母改名为 `citationBoundaryAccuracy`、`verificationEligibleRecall`、`verificationRejectionSpecificity` 和 `claimQuoteFixturePassRate`；简单 token overlap 只用于确定性 fixture 回归，不作为语义蕴含质量证据。空分母不再自动得到 1.0。

## 真实 Agent Trace 评分

以 [ai-news-labeled-traces.example.json](examples/ai-news-labeled-traces.example.json) 为格式起点。该文件只是 schema 示例，不能被当作评测结果。

对每个抽样事件，复核者应私下保留不可变的来源快照，并标注：

- 来源分级，以及候选是否实际满足核验准入；
- 所有事实引用是否都来自已归档的 Evidence Packet；
- 每条关键 claim 是否被 quote 支持；
- 规范事件标识、应否拒绝、未解决冲突、任务结果、工具选择、工具参数和人工复核路由。
- `prediction.refusalIssued` 与 `prediction.outputValid`；无效输出应保留仍可解析的字段，缺失字段保持 `null`。

对有争议的事实或蕴含关系使用两位独立复核者，并记录最终裁决；在 `executionMetadata` 写入复核协议。原始 Prompt、来源快照、用户/渠道标识、API Key 和可能受授权限制的网页副本不要进入 Git。应在私有证据库中保留它们的 hash 或受控引用。

formal 受控评测也执行同一诚信门禁：数据元数据必须明确 `labelReviewStatus=two-independent-reviewers-complete`，运行时提供两份不同 reviewer signoff，且 case order 必须在冻结数据中预声明。当前关系集、自主工具集和 sealed v2 都没有真实双人签署，因此 runner 会拒绝将它们作为 formal 运行；不能由生成者或本 Agent 代替第二位人工签字。

对一个已经标注的数据集执行：

```bash
./scripts/score-ai-news-traces.sh /absolute/path/to/labeled-traces.json
```

可选的第二个参数指定输出目录。脚本只读取输入并生成 JSON Manifest 与 Markdown 报告，不会修改数据集。模型或 Prompt 的 A/B 比较必须使用同一个冻结 development 数据集和相同切片定义，并同时报告样本量、区间、badcase 和聚合指标；Prompt 调整完成后只能在此前未查看的冻结 holdout 上形成最终结论。v3 已被反复用于诊断和 Prompt 调整，因此后续在 v3 上的提升只能算 development 结果。

## 面试安全表述

离线结果可以支持以下表述：

> 构建版本化的 30 样例 Evidence Policy 回归基准，输出来源分级准确率、核验/拒绝/引用 P-R-F1、事件去重 Pairwise F1、场景切片及 JSON/Markdown badcase 工件；全程不依赖模型凭证和外部网络，可在 CI 与本地复跑。

不要将策略基准分数包装为线上模型准确率。完成真实 trace 标注后，只有同时提供采样窗口、事件数量、模型/Prompt/路由版本、人工复核协议和精确分数，才可以在简历中写发现准确率、引用质量或幻觉率。

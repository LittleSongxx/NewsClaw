# AI Dynamic Controlled Live Agent Evaluation

## What It Measures

`scripts/run-ai-news-live-agent-eval.sh` runs a frozen, synthetic 30-case benchmark through a **running** NewsClaw instance. Each case makes a real authenticated SSE request to an AI-news Agent, follows its actual model route, records the returned stream, and scores the final structured decision with deterministic labels.

其中的质量分数是与**冻结合成金标**的一致性，不是人工对真实用户流量的评分。特别是同名的 `claimQuoteSupported`、`taskSuccess` 和工具指标，在这一层只能说明受控证据协议是否被遵守；真实业务质量仍须使用第三层人工标注 trace 评分。

This is deliberately different from both other evidence layers:

| Evidence layer | Input and executor | What it can support | What it cannot support |
| --- | --- | --- | --- |
| Offline policy regression | Versioned fixtures, production policy services, no model/network | State-machine, source-registry, citation-boundary and URL-dedup regression | Live model quality or latency |
| Controlled live Agent benchmark | Frozen synthetic evidence packets, real SSE/model/read-only tool | End-to-end structured decision, refusal, citation boundary, HITL routing, tool discipline, TTFC and per-run latency | Provider TTFT, open-web discovery accuracy, production traffic capacity, user satisfaction |
| Human-labeled trace scoring | De-identified sampled real Agent traces and reviewer labels | Real task success, Claim-Quote support, tool correctness and routing quality | Reproducibility without the retained private evidence |

The frozen v3 benchmark uses 24 no-tool cases and 6 required read-only `ai_news_event(action=source_health)` probes. In v3, no-tool cases send `toolChoice=none`; required cases send `toolChoice=function:ai_news_event`. Both 100-case sets use 80 no-tool cases and 20 required probes. None of these datasets asks the Agent to write an event, create a Wiki page/content, send a channel message, request approval, or publish externally. The fixed cases cover Chinese and English, official sources, official GitHub prefixes, independent media corroboration, single media, community/lookalike sources, quote mismatch, unarchived citations, unresolved conflict, and tool parameters.

P0 改造前的两轮真实对照见[2026-08-25 受控在线基线归档](evidence/ai-news-controlled-live-baseline-20260825.md)；改造后从干净提交重建并执行的 30 条完整结果、Provider 根因矩阵、badcase 和工件哈希见[v3 证据归档](evidence/ai-news-controlled-live-v3-20260825.md)。两个归档分别冻结各自协议和目录，后续结果不会覆盖历史数字。

新的 100 条候选集见[100 条候选集](evidence/ai-news-holdout-100-20260826.md)。它第一次使用独立的 `live-agent-evidence-v4-holdout` Prompt 得到 84/100，随后被用于多轮 development 调优，已经失去 unseen holdout 状态；结果与数据充分性复盘见[首轮结果与复盘](evidence/ai-news-holdout-100-first-look-20260826.md)。默认运行入口仍然是 v3。

在冻结当前 v8 Prompt/Skill/来源规则后，又生成并一次性运行了不复用中英翻译场景的 `sealed holdout v2`。候选首看为严格任务成功 89/100、来源等级与 Claim-Quote 支持各 100/100、核验准入 91/100、引用准入 90/100、HTTP/SSE/严格 JSON 100/100；预注册、切片、badcase 和工件哈希见[sealed v2 首看](evidence/ai-news-sealed-holdout-v2-20260826.md)。首次运行时数据尚未人工复核且运行树 dirty，所以不是 formal baseline；事后 A/B 决策已由两位人工确认，但不会追溯恢复 unseen/formal 身份，查看结果后仍只作为 regression set。

针对 sealed v2 的失败模式，当前开发协议新增 `ai_news_evidence_relations_v2`：模型只返回每条 evidence 对完整 claim 的 `entails/contradicts/partial/unrelated/hedged + confidence`，后端再按真实来源注册表、独立 publisher 数、高风险规则、可信冲突和调用方 citation allowlist 确定性生成核验/引用结论。v9/v1 首轮暴露出 policy 输入串扰：模型思考正确但终态漏 ID 或翻转，任务 24/30、关系项 39/50。v10 的 `live-agent-evidence-relations-development-v2.json` 复用相同 30 个已见 badcase，但模型视图只保留 `primaryClaim + evidenceId + quote`，末端固定完整 ID 序列；三种预声明顺序共 90/90 case、150/150 关系项通过且 0 flip。它是针对已知失败的稳定性回归，不是 holdout 或泛化分数。

工具能力另拆为 `live-agent-tool-autonomy-development-v2.json`：30 条全部发送 `toolChoice=auto`，15 条应自主选择一次只读 source-health 操作、15 条应不调用；期望工具名和参数只存在于 scorer 字段，不会进入 runner 指令。单轮工具选择 29/30、必需参数 14/15，说明 AUTO 是可测的模型能力而非保证执行的控制面。针对漏调加重措辞的 v3 已见调优反而降至 28/30、13/15，因此不选作推荐协议。明确必做动作应使用 exact-function；AUTO 只用于允许模型选择或克制的请求。无效参数后恢复、HTTP 超时后恢复和 SearXNG 开放检索 provenance 由独立确定性测试覆盖，不与语义关系总分混合。

2026-08-27 的 v4 是 v2 的纯执行协议 overlay：所有 case/prompt/gold/policy packet 不变，统一发送 `toolCandidates=["ai_news_event"]`，并把 `source_health` 工具合同明确为只需要 action。首组与最终源码的三种预声明顺序均为 89/90、required 44/45、forbidden 45/45；首组唯一失败先用空参数调用后正确重试，最终源码唯一失败则是 thinking 明确认识要调用却完全没有工具事件。候选集只与权限过滤、渐进披露后的 active callbacks 取交集，缺失名称 fail-closed，并在 `stream_started` 回显。执行器还把回调返回的 `Error:` 业务错误按原始结果记为失败，而不是仅以“未抛异常”判断成功。重复英文 action 合同的实验因逆序退化至 27/30 已撤销。完整结果见 [v4 优化记录](evidence/ai-news-tool-autonomy-v4-optimization-20260827.md)。

对兼容的 AI 动态七字段终态调用，发送 `responseSchema=ai_news_decision_v1`；新的语义关系协议发送 `ai_news_evidence_relations_v2` 和精确 `expectedEvidenceIds`。两者都保持 `responseFormat=json_object`；未声明命名 schema 的旧客户端仍按通用 JSON 对象处理。

百炼 Qwen 对命名 schema 偶发返回“一个合法 JSON 对象外包一层 Markdown fence”。服务只在完整、非 partial、无工具终态中接受一个无前后缀的 `json`/裸围栏，并要求围栏内通过重复键、trailing token、对象类型及对应业务 schema 校验；成功时发 `structured_output_reconciled(reason=single_json_fence_validated)`。解释文字、多围栏、数组、错误字段/ID/顺序仍由原严格合同拒绝。runner 将恢复原因和比率单独记账，所以这一兼容层不会静默伪装成模型原生合规。

默认的 v3 协议发送 `responseFormat=json_object`、`responseSchema=ai_news_decision_v1` 和显式 `toolChoice`。原生 OpenAI-compatible ReAct 路径把一次 Agent turn 分成两个受约束阶段：`required/function` 只强制首个 assistant 工具步骤；工具结果返回后，终态步骤自动切换到 `toolChoice=none` 并启用原生 JSON Object 约束。这避免重复强制工具，也兼容不能在同一 provider 请求中组合 exact-function 与 JSON mode 的路由。Web 层再对最终 assistant 文本执行严格 JSON Object 校验；`stream_started` 会确认 response format、语义 schema 和工具策略，`structured_output` 会报告服务端校验状态。runner 还使用独立严格解析器检查 Markdown fence、非对象、缺字段、重复 key、额外字段、trailing token 和精确引用列表，并记录服务端与 runner 是否一致；缺少 schema 回显会单独计入 `responseSchemaAcknowledgedRate` 并使该例失败。

公开请求值为 `auto`（默认）、`none`、`required` 和 `function:<exact-tool-name>`。可选 `toolCandidates` 进一步限制 provider-visible schemas：`null` 保持全部 active 工具，空数组暴露零工具，非空数组只做交集且不能扩大权限。精确函数和候选名只有已通过 Agent 权限过滤和渐进披露、仍在 active callback 集合中才有效；它们不绕过 Tool Guard、参数校验、人工审批或执行器作用域。Plan Execute、外部 Agent runtime 或不支持所需 JSON 能力的协议会在工具执行前显式失败，不会静默降级。旧客户端不传字段时仍保持 `toolChoice=auto`、候选集 unrestricted 与 `responseFormat=text` 行为。

工具回调若按历史约定返回 `Error: ...`（包括 Spring JSON 编码 String 时的外层引号），普通执行和审批后重放都会发出 `success=false` 回执；判定使用 spill/truncate 前的原始结果，避免长错误被预览文本掩盖。这个规则不把任意含 `error` 字段的 JSON 当失败，也保留旧 null/void callback 的成功语义。

v3 的工具结果必须按“provider 强制编排 + NewsClaw 执行器正确性”解读。因为调用方已经声明 `none` 或精确函数，它不能证明模型自主选择了正确工具；自主选择能力仍需单独使用 `toolChoice=auto` 的冻结数据集测量。v2 保留为历史自主选择基线，不与 v3 的工具选择分数直接对比。

`humanReviewRequested` 在本基准中只是模型对冻结场景的解释性决策。持久化复核任务的创建、风险变化后重开、显式人工解决和生产门禁由确定性后端策略与服务测试证明，不能用该模型字段或飞书发卡成功率替代。

## Run It

The runner intentionally receives credentials only through environment variables. It does not put passwords on a Maven command line, in an artifact, or in Git.

```bash
export NEWSCLAW_EVAL_USERNAME='admin'
export NEWSCLAW_EVAL_PASSWORD='your-local-password'
export NEWSCLAW_EVAL_AGENT_ID='your-ai-news-editor-agent-id'
export NEWSCLAW_EVAL_BASE_URL='http://127.0.0.1:18080'

./scripts/run-ai-news-live-agent-eval.sh
```

To select the Agent id from a local deployment, authenticate normally and inspect `GET /api/v1/agents` for the AI-news editor/lead. The optional environment variables are:

| Variable | Default | Meaning |
| --- | --- | --- |
| `NEWSCLAW_EVAL_WORKSPACE_ID` | `1` | Workspace header used for the benchmark conversations. |
| `NEWSCLAW_EVAL_TIMEOUT_SECONDS` | `240` | Per-stream timeout. |
| `NEWSCLAW_EVAL_MAX_CASES` | `0` | `0` means all cases in the selected dataset; a positive number is only a prefix smoke run and is not a representative quality estimate. |
| `NEWSCLAW_EVAL_RESPONSE_FORMAT` | `json_object` | Use `text` only for an explicit backward-compatibility comparison. |
| `NEWSCLAW_EVAL_PROMPT_VERSION` | 数据集元数据值 | 覆盖 Prompt renderer；覆盖冻结值会在工件中标记为 development reuse。 |
| `NEWSCLAW_EVAL_THINKING_LEVEL` | 跟随 Agent/模型默认 | `off/low/medium/high/max`；百炼 Qwen 的 `off` 会映射为 `enable_thinking=false`，实际值写入执行元数据。 |
| `NEWSCLAW_EVAL_RUN_CLASS` | `development` | `development/candidate/formal`；formal 会启用严格准入门禁。 |
| `NEWSCLAW_EVAL_CASE_ORDER` | `dataset` | `dataset`、`reverse` 或 `rotate-N`；用于预声明的顺序稳定性测试。 |
| `NEWSCLAW_EVAL_SERVER_REVISION` | 本地 Docker 尽量自动解析 | 被测服务镜像或部署版本；formal 必填。 |
| `NEWSCLAW_EVAL_PRIMARY_REVIEW_SIGNOFF` | 空 | 第一位真实标签复核者的冻结签署标识；formal 必填。 |
| `NEWSCLAW_EVAL_INDEPENDENT_REVIEW_SIGNOFF` | 空 | 第二位独立复核者的冻结签署标识；formal 必填且必须与第一位不同。 |

An optional first argument replaces the benchmark JSON. An optional second argument replaces the output directory:

```bash
./scripts/run-ai-news-live-agent-eval.sh path/to/frozen-benchmark.json target/evals/20260825
```

The runner writes these untracked artifacts:

```text
target/ai-news-live-agent-evaluation/<utc-run>/
  live-agent-evidence-v3.traces.json
  live-agent-evidence-v3.quality-manifest.json
  live-agent-evidence-v3.quality-report.md
  live-agent-evidence-v3.runtime-manifest.json
  live-agent-evidence-v3.runtime-report.md
  raw/<run-id>/<case-id>.sse
```

The quality manifest uses the same scorer as offline fixtures and human-labeled traces. The runtime manifest (schema `3.3`) additionally stores requested/observed tool candidates and any structured-output reconciliation reason, alongside the response format/schema/tool choice, server/independent-parser agreement, usage, latency, route, failures, and raw-output hashes. It does not store a JWT or login response. Raw synthetic SSE is retained under `target/` and is excluded from Git.

新工件还在 quality execution metadata 中记录 `benchmarkSha256`、`promptContractSha256`、`evaluationSourceFingerprint`、`serverRevision`、声明/实际 Prompt、`runClass` 和 formal 门禁状态。旧工件若只有 `Git SHA + dirty`，不能据此判断两次运行使用了相同代码。

## Metric Interpretation

The quality report includes source-tier accuracy and macro-F1, verification/refusal/citation/Claim-Quote P-R-F1, human-review routing, tool selection and tool parameter correctness, per-slice metrics, and badcases. In this report these are frozen-protocol labels, not human judgments of real user tasks. A controlled task is successful only when all of these hold:

1. HTTP succeeds and the SSE stream reaches `completed`.
2. The server acknowledges `json_object`, the case-declared named schema, and the requested `toolChoice`, emits a valid `structured_output` result, and the independent parser agrees.
3. v1 的终态必须是严格七字段对象；v2 必须对 `expectedEvidenceIds` 中每个 ID 恰好返回一次合法关系，不能增删、重复或改写 ID。
4. v2 先评分逐项语义关系，再用生产 `AiNewsDecisionPolicy` 计算策略字段；模型不再自行聚合来源/核验/引用政策。
5. v1 citation ids 仍必须在允许时精确等于 `[requestedCitationId]`、否则为 `[]`；额外或重复 ID 失败。v2 的后端聚合也执行相同 allowlist。
6. The required tool/no-tool expectation and required tool outcome are correct.

The runtime report uses nearest-rank P50/P95 over this one **sequential** run. `timeToFirstContentMs` is TTFC: time to the first visible final-answer `content_delta`; hidden reasoning and tool events may already have happened, so it is not provider TTFT. Prompt tokens include provider-reported cached input; cache read/write, uncached input and hit ratios must be read together. Rate metrics include Wilson 95% intervals, and small samples emit warnings. These observations are useful for paired regression direction, but they are not a QPS benchmark, capacity test, SLA, latency promise, production cost figure, or statistically representative traffic measurement.

`live-agent-evidence-v4-development` is a candidate orthogonal decision-table Prompt. It separates strongest source, trusted quote support, conflict, verification and requested-citation support, but it was designed after inspecting v3 badcases. Any score on v3 is development evidence only. A publishable v4 conclusion requires a separately authored, frozen holdout that Prompt authors did not inspect during tuning, plus repeated runs and per-case flip rates.

`live-agent-evidence-v5-development` is the follow-up development protocol for the two observed gaps: it states a truth table for conflict versus quote support and makes the high-risk safety-source rule explicit. `C=true` or `D=false` must not overwrite the independent `claimQuoteSupported` decision. It is not a replacement for the frozen v4 holdout; running it against an already inspected dataset is development evidence only.

The repository contains a frozen 100-case dataset that originally used the `live-agent-evidence-v4-holdout` contract. It has now been run and reused for development, so run `./scripts/validate-ai-news-holdout.sh` before regression use but do not present any later score as unseen holdout evidence. The first-look result and repeatability audit are recorded in [the 100-case review](evidence/ai-news-holdout-100-first-look-20260826.md).

`live-agent-evidence-sealed-holdout-v2.json` is separately frozen at SHA-256 `76b2d9df35506cfc17f639d417b1395039c5e1528b7581d867a65a0d7e0eb00c`. Run `./scripts/validate-ai-news-sealed-holdout-v2.sh` to check payload composition、old-set non-overlap、来源标签和冻结 v8 的 rendered Prompt contract。脚本会报告首看组件与当前实现是否已分叉，而不会要求生产代码永远停在旧字节；其唯一 unseen observation 已完成，未来只能做 regression/repeatability。

多个相同数据集工件可用以下入口检查运行身份、分数区间和逐例翻转；只有完整比较签名一致且来源可复现的运行才应做重复性归因：

```bash
node scripts/analyze-ai-news-live-runs.js \
  --output-json target/ai-news-live-agent-evaluation/repeat-analysis.json \
  --output-markdown target/ai-news-live-agent-evaluation/repeat-analysis.md \
  target/ai-news-live-agent-evaluation/<run-1> \
  target/ai-news-live-agent-evaluation/<run-2>
```

也可以用固定的 `dataset → reverse → rotate-10` 顺序一次执行 3 轮 development 稳定性评测（可用 `NEWSCLAW_EVAL_REPEATS=3..5`）：

```bash
./scripts/run-ai-news-live-agent-stability.sh \
  newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-relations-development-v2.json
```

聚合 schema 1.1 同时报告每轮/最差/均值/最好值、逐例成功率、相邻轮翻转率、逐语义组成功率和不稳定组。重复轮共享同一批 case，只是 development 稳定性观察，不能合并成更大的样本或再次声称 unseen。

2026-08-25 的 development 对照中，`thinkingLevel=off` 虽将三轮 TTFC P50 稳定降到约 1.05~1.12s，但严格质量只有 26~27/30，低于 v4/model-default 单轮的 29/30，且全量缓存命中率不同。因此它只保留为显式实验开关，不作为 Qwen 默认设置；完整归因与逐例翻转见[评测体系审计](evidence/ai-news-evaluation-audit-20260825.md)。

## Formal Baseline Checklist

Before quoting an online controlled result in a portfolio or interview:

1. Commit the benchmark runner, fixed dataset, scoring code, and documentation.
2. Rebuild/restart the evaluated server from that commit. Do not score a stale container and label it as current code.
3. 新数据必须完成两位真实、相互独立的逐条标签复核，把 `executionMetadata.labelReviewStatus` 冻结为 `two-independent-reviewers-complete`，并预声明允许的 case order。运行时设置 `NEWSCLAW_EVAL_RUN_CLASS=formal`、`NEWSCLAW_EVAL_MAX_CASES=0`、服务版本和两份不同 reviewer signoff；runner 会在登录/模型调用前拒绝 dirty tree、Prompt override、部分样本、未预声明顺序、待复核标签或缺失身份。
4. Retain manifests plus raw-trace SHA-256 values and record the Git SHA, source/dataset/Prompt/service fingerprints, model route, case count, run date, badcases, and P50/P95 values together.
5. Repeat the same frozen protocol with predeclared orderings and report per-run results plus case/semantic-group flips; do not merge repeated observations as independent cases.
6. Describe the result as a **controlled live Agent benchmark**. Keep it distinct from the human-reviewed sampled trace results described in [AI dynamic quality evaluation](ai-news-quality-evaluation.md).

Safe wording after a clean run is:

> Implemented a versioned 30-case controlled live Agent benchmark that drives the real NewsClaw SSE/model route and provider-enforced read-only tool path, emits quality pass-rate/P-R-F1 with Wilson intervals plus route/cache-token/TTFC/E2E artifacts, and keeps raw traces and badcases auditable. Tool metrics cover declared orchestration plus executor correctness, not autonomous model selection; the result is bounded to frozen evidence-policy scenarios and is not presented as provider TTFT, online discovery accuracy or throughput.

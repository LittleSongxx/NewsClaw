# AI 圈最新新闻搜集：P0 垂类质量评测

这套评测回答的是 NewsClaw 的业务主问题：在一个明确的 AI 新闻来源范围和时间窗口内，系统是否及时、完整、低噪声、低重复地发现了重要事件，并给出了可核查证据。它不替代已有 Agent 协议评测；Agent 评测继续作为底层护栏。

## 已实现入口

- 确定性计分器：`AiNewsDiscoveryQualityEvaluator`
- Markdown 报告：`AiNewsDiscoveryQualityReportRenderer`
- 严格 JSON 契约：`newsclaw-server/src/test/resources/evals/ai-news/discovery-quality-dataset.schema.json`
- 算术回归样例：`newsclaw-server/src/test/resources/evals/ai-news/discovery-quality-fixture-v1.json`
- 运行入口：`scripts/eval-ai-news-discovery-quality.sh`
- 在线快照账本：V211 `ai_news_discovery_run`，管理 API 为 `/api/v1/ai-news/discovery/**`
- 同窗稳定性计分：`AiNewsDiscoveryStabilityEvaluator`（Jaccard@10/30、RBO@10/30）

计分过程不联网、不调用模型。联网只用于确定方法；正式分数完全由冻结数据、独立标注和确定性公式得到。

## 为什么采用这些方法

| P0 质量问题 | 具体实现 | 成熟依据 |
| --- | --- | --- |
| 找到了多少、噪声和重复多少、发现是否及时 | 原子事件金标、event recall、relevance/novel precision、Recall@T、TREC latency discount | [NIST TREC 2015 Temporal Summarization](https://trec.nist.gov/pubs/trec24/papers/Overview-TS.pdf) |
| 相同新闻是否正确聚为一个事件 | B³ precision、recall、F1；pairwise merge/split 作诊断 | [Bagga & Baldwin, COLING 1998](https://aclanthology.org/C98-1012/)、[Amigó 等人的聚类指标比较](https://doi.org/10.1007/s10791-008-9066-8) |
| 重要新闻是否排在前面 | snapshot-level nDCG@K、novel Precision@K、Recall@K，然后做宏平均 | [NIST trec_eval nDCG 实现](https://github.com/usnistgov/trec_eval/blob/main/m_ndcg_cut.c)、[Järvelin & Kekäläinen 2002](https://doi.org/10.1145/582415.582418) |
| 事实是否有完整且相关的引用 | 原子 claim 的 citation recall、claim-citation relation 的 citation precision | [ALCE，EMNLP 2023](https://aclanthology.org/2023.emnlp-main.398/) |
| 评测是否可重复 | 明确任务、固定输入 schema、独立 grader、保存运行上下文和数据哈希 | [OpenAI 官方 Evals 指南](https://developers.openai.com/api/docs/guides/evals) |

TREC 的目标是评价实时信息流中的 relevance、coverage、novelty 和 latency；其人工流程先建立带时间戳和重要性等级的原子信息单元，再将系统更新匹配到金标。这与“AI 新闻事件账本 → NewsClaw 事件卡片”的结构直接对应。

## 先拆开代码回归与网络漂移

联网搜索结果会随索引刷新而变化，不能把前后两次实时查询直接当成代码 A/B。生产发现现在为
每次查询通道保存规范化请求、完整返回、`fromCache`、通道结果哈希，并计算两级内容地址：

- `snapshotHash`：窗口、查询参数和逐通道原始返回的身份；
- `rankingHash`：策略版本、窗口和最终有序候选的身份。

`POST /api/v1/ai-news/discovery/runs/{id}/replay` 不联网地把当前策略应用到冻结响应。只有
`snapshotHash` 相同时，前后候选差异才能归因给代码或配置；同一快照、同一策略的
`rankingHash` 和候选载荷必须完全一致。

真实网络 sentinel 则至少独立运行三次，并清空进程内搜索缓存。运行必须属于同一 workspace、
使用相同半开 UTC 窗口和完整策略版本；任一通道 `fromCache=true` 时报告只作诊断，不能作为
实时稳定性 SLA。稳定性报告使用：

```text
Jaccard@K = |两次 Top-K URL 集合交集| / |两次 Top-K URL 集合并集|

RBO@K = 对前缀重合按排名深度加权；当前 persistence p = 0.90
```

Jaccard 回答“结果集合是否换了”，RBO 还惩罚顺序漂移。报告同时保留
`identicalSnapshotPairRate` 与 `identicalRankingPairRate`：原始快照不同但排序相同，表示准入策略
吸收了索引噪声；两者都相同则可能是供应商稳定，也可能是缓存，所以必须结合 `fromCache` 判断。
这些重复性指标不替代有人工金标的 Recall/Precision；稳定地返回错误结果仍然是错误。

## 指标口径

### 1. 事件发现与有效精确率

每个 `goldEvent` 是一条原子、互不重复、在来源宇宙内应当发现的事件，重要性为 1～3。每个 `systemEvent` 经独立标注后最多匹配一个 `matchedGoldEventId`；未匹配结果必须填写原因。

基础指标：

```text
eventRecall = 被至少一个系统结果命中的不同冻结金标事件数 / 冻结金标事件总数

goldMatchPrecision = 能匹配冻结金标的系统结果数 / 输出窗口内系统结果总数

relevancePrecision = 人工独立判为相关的系统结果数 / 已完成相关性裁决的输出结果数

novelEventPrecision = 不同人工相关事件身份数 / 已完成相关性裁决的输出结果数
```

匹配冻结金标的结果自动算相关；未匹配冻结金标不等于不相关，因为冻结账本可能不穷尽。
这类结果只有在人工填写 `adjudicatedRelevant=true` 和稳定的 `adjudicatedEventId` 后才进入
相关性/新颖性分子。`adjudicatedRelevant=false` 才是明确假阳性；缺失该字段的开放世界行记为
`unknown`，保留在计数中但不进入 precision 分母。`relevancePrecision` 中，同一事件的重复卡片仍算相关；
`novelEventPrecision` 中，重复卡片的新增收益为 0。因此二者的差距显示重复损耗，
而 `goldMatchPrecision` 单独回答对冻结账本的覆盖，不能在不完整金标上冒充真实精确率。

为了避免“事件找对了，但事实和证据不可用”仍获得满分，报告额外给出透明的交集指标：

```text
evidence-ready =
  已匹配金标，或被人工判为相关且具有稳定事件身份
  AND 至少有一个原子可核查 claim
  AND 所有可核查 claim 均被联合引用完整支持
  AND 至少一个合法 HTTP(S) 证据 URL 实际抓取成功
```

对应输出：

- `retrieval.evidenceReadyEventRecall`
- `retrieval.evidenceReadyOutputPrecision`
- `retrieval.evidenceReadyNovelPrecision`
- `retrieval.evidenceReadyF1`

这个交集指标是 NewsClaw 的产品口径，不冒充 TREC 原始指标；原始事件召回、相关精确率和证据指标仍分别保留，便于定位问题。
它不是完整的 `technical_ready`、`semantic_ready` 或 `publish_ready` 门禁：当前 evaluator
已额外输出 `readiness.technicalReadyRate` 与 `readiness.semanticReadyRate`，但 rights/robots、
quote exact、冲突披露和发布审批仍由发布侧门禁负责；在数据集没有人工批准和平台回执时，
`readiness.publishReadyRate` 必须为 N/A。

重要性加权召回使用 TREC 的指数重要性：权重与 `exp(importance)` 成正比。由于分子和分母使用相同归一化常数，代码中可直接使用 `exp(importance)`。

### 1.1 候选层召回诊断

`discoveryCandidates` 保存 `discover` 返回的完整候选排名，并由人工只做“是否与某条冻结
金标是同一底层事件”的保守匹配。报告输出：

- `candidate.goldEventRecall` 与 `candidate.goldEventRecallAtK`：搜索/结构化来源在进入 Agent 选择前覆盖了多少不同金标；
- `candidate.goldMatchCardPrecision`：候选卡片中匹配冻结金标的比例；
- `candidate.uniqueGoldMatchPrecision`：不同金标匹配数除以候选卡片数；
- `candidate.duplicateGoldMatchRate`：已匹配卡片中重复命中同一金标的比例。

这组指标用于把“上游根本没召回”与“已召回但 Agent 没选择/抓取”分开。冻结金标不完整时，
未匹配候选必须标为 unknown，不可自动计作假阳性；因此 `candidate.goldMatchCardPrecision`
也是账本匹配率，不是开放世界真实 precision。RSS/Atom 的发布时间和摘要与搜索 provider 的
`publishedAtHint` 一样，只能参与候选排序，不能当作证据或最终发布时间。

### 2. 新鲜度与发现时延

每个金标事件只取最早匹配的系统结果：

```text
lag = max(0, detectedAt - firstPublishedAt)
Recall@T = lag <= T 的金标事件数 / 金标事件总数
```

默认报告 `Recall@30m`、`Recall@120m` 和 `Recall@1440m`，但这些是项目 SLA 配置，不是行业统一阈值。

同时实现 TREC 的平滑时延折扣：

```text
L(lag) = 1 - (2 / π) × atan(lag / α)
默认 α = 360 分钟
```

输出 `freshness.latencyAdjustedRecall` 和 `freshness.latencyAdjustedNovelPrecision`。TREC 原公式允许金标时间不精确时出现负 lag；NewsClaw 正式回放默认不奖励“提前发现”，而是禁止超出 `earlyDetectionToleranceMinutes` 的提前时间，并将容差内负值按 0 计。这样能把未来数据泄漏或错误发布时间直接暴露出来。

计分器将时延换算为整数分钟并向上取整（例如 60.001 秒记为 2 分钟），所以 cutoff
边界必须按该 `ceil` 口径解释；原始时间戳仍须保留，不能只保存整数 lag。

P50/P90 只在已发现事件上计算，因此报告明确警告必须与 Recall@T 一起阅读。百分位使用常见的线性插值定义。

为避免右删失，计分器强制：

```text
observationEndAt >= endAt + 最大 freshness cutoff
```

例如测 Recall@24h，金标窗口结束后必须再保留至少 24 小时观察尾窗。尾窗结果可以补足窗口内金标事件的召回，但不进入输出窗口的精确率分母。

`firstPublishedAt` 应取来源宇宙内最早公开时间，统一成 UTC。页面同时显示“发布时间”和“更新时间”时应保留原始发布时间；日期、时区和正文中的时间应交叉核验。[Google 的发布日期技术说明](https://developers.google.com/search/docs/appearance/publication-dates)也要求日期清晰、一致并使用正确时区。

### 3. 去重与事件聚类

`clusterAssignments` 针对同一批候选文章或来源条目保存：

- `goldClusterId`：人工确认的真实事件簇；
- `predictedClusterId`：NewsClaw 实际聚类结果；
- `itemId`：稳定的来源条目标识，建议使用 canonical URL hash。

正式评测还必须在 `executionMetadata.clusterUniverseItemCount` 声明该批次的完整条目数；计分器会输出
`clustering.assignmentCoverage`，并在声明缺失或只提交子集时把 `evaluationEligible` 置为 `false`。
否则只给一小段容易聚类的样本就能虚高 B³，不能作为生产质量结论。

仅有数量仍不足以证明没有换样本。正式数据还必须在
`executionMetadata.clusterUniverseItemIds` 写入完整条目集合（建议按稳定 ID 排序，便于 diff）；计分器会
按集合逐项比对，当前不把逗号分隔值的顺序当作语义，并拒绝重复或跨 system/candidate/evidence 命名空间复用的 ID。候选层若存在，
还必须声明 `candidateAdjudicationStatus=complete`，否则候选 Recall 只能作为开发诊断。

`evaluationEligible` 及上述 execution metadata 是协调员提交的声明性门禁字段。计分器能够校验
字段值、集合覆盖和标注结构，但不能仅凭该 JSON 证明双人独立标注、独立 collector、冻结来源
快照、时间切分隔离或抓取 as-of 历史；正式报告仍须附签署记录、快照/数据哈希和 immutable
capture 日志。`futureLeakage=PASS` 同样只是声明，不替代这些审计证据。

B³ 对每个条目 `i` 计算：

```text
B3 precision(i) = |预测簇(i) ∩ 金标簇(i)| / |预测簇(i)|
B3 recall(i)    = |预测簇(i) ∩ 金标簇(i)| / |金标簇(i)|
```

然后对条目取平均并计算 F1：

- precision 低，通常说明把不同事件错误合并；
- recall 低，通常说明同一事件被拆成多个簇。

报告还用组合计数而不是 O(N²) 枚举，计算 pairwise precision/recall、`overMergePairRate` 和 `overSplitPairRate`。B³ 是主指标，pairwise 只作故障解释。

最终事件流的重复泄漏另行报告：

```text
redundantOutputRate = 重复匹配卡片数 / 所有输出卡片数
duplicateLeakageAmongRelevant = 重复匹配卡片数 / 所有相关卡片数
```

### 4. 证据可信度

正式数据先把输出拆成原子可核查 claim，再由独立标注确认：

- 多条引用联合起来是否完整支持 claim：`jointlySupported`；
- 每条引用与 claim 的关系：`entails / partial / contradicts / unrelated / hedged / unknown`；
- 来源真实等级、抓取是否成功、发布时间是否正确。

按 ALCE 的人评口径：

```text
claimCitationRecall = 被联合引用完整支持、且证据 URL 可抓取、发布时间正确的可核查 claim
                     / 所有可核查 claim

citationPrecision =
  claim 已获完整联合支持，且该 citation 对 claim 为 entails 或 partial 的关系数
  / 所有可核查 claim-citation 关系数
```

这里使用人工确认的关系，不在正式计分时再让 NLI/LLM 自动判断。这样不会把 grader 模型误差混进 NewsClaw 的产品分数。
若同一可核查 claim 同时出现 `contradicts` 关系，事件不会进入 evidence-ready；证据发布时间晚于
系统首次观测时间会直接被拒绝为未来信息泄漏。`futureLeakage=PASS` 仍只是回放审计声明，不能替代
抓取 as-of 日志；生产实现必须保存 URL 版本/抓取时间以证明页面在当时可见。

同时报告：

- 事件证据覆盖率；
- 一手/官方来源覆盖率；
- 至少一个证据成功抓取的事件覆盖率；
- 证据抓取成功率；
- URL 合法率；
- 来源发布时间覆盖率与正确率；
- URL、标题、时间、来源等级全部齐全的 provenance completeness；
- 系统来源等级与人工等级的一致率。

报告中的 `evidence.fetchSuccessRate` 只以已经挂到最终系统事件的 evidence packet 为分母。发现流程还应在 execution metadata 中另报所有 `capture_source` 尝试的成功率、成功抓取后的时间解析覆盖率和被时间窗拒绝的数量；否则只看最终证据会隐藏抓取失败与选择损耗。

### 5. 重要性排序

排序必须评测某个时间点用户真实看到的列表，而不是事后把全窗口事件排一次。`rankingSnapshots` 保存：

- snapshot 时间；
- 当时页面或 API 的系统事件 ID 顺序。

计分器从 snapshot 之前 `rankingLookbackMinutes` 的金标事件构造 qrels。默认 lookback 为 24 小时；为了避免历史金标不完整，最早 snapshot 必须位于 `startAt + lookback` 之后。

每个 snapshot 独立计算后再宏平均：

- `nDCG@K`：重要性 1～3 作为线性 gain，位置折扣为 `log2(rank + 1)`，与 NIST `trec_eval` 默认 gain 口径一致；
- `novelPrecision@K`：Top K 中不同、有效的金标事件比例；
- `eventRecall@K`：该 lookback 内金标事件进入 Top K 的比例。

同一金标事件在一个列表中只有第一次出现获得 gain，后续重复项按 0 计。未返回的位置也按 0 计，因此返回不足 K 条会影响 Precision@K。
如果某个 snapshot 的 lookback 内没有任何金标（empty qrel），该 snapshot 的三个排名指标记为
`N/A`，从宏平均排除，并在 `ranking.eligibleSnapshotRate` 中报告。当前 evaluator 对含有
empty-qrel 的数据集会把 `evaluationEligible` 置为 `false`，要求在正式 split 中重建窗口；
未来若支持“记 0”或其他显式 empty-qrel policy，必须先把 policy 写入 manifest 和数据切分说明，
不能静默当作 0 或直接丢弃。

## 正式数据如何构建

### 当前工程回放示例，不是生产基准

2026-08-28 的发现准入 v5 检查点已将“代码因果回归”和“实时索引漂移”拆开。对同一冻结
`snapshotHash` 重放，策略从 temporal v2 的 30 条（22 窗口内提示 + 8 未知）收敛为 v5 的
23 条（20 + 3），明确窗口外始终为 0，并加入最多两个独立 publisher 的故事折叠。三次清空
进程缓存的 Tavily-only 同窗运行均为 23 条（20 + 3 + 0），未知且未注册开放 Web 为 0；
Jaccard@10 为 1.0，Jaccard@30 为 0.769231，RBO@10 最差 0.954775，RBO@30 最差
0.940357，`liveSentinelEligible=true`。一次即时结果的离线重放在 snapshot/ranking hash 和候选
载荷上完全相同。

这些数字只证明单窗口准入与短时重复性，不是人工相关性、搜全率或生产 SLA。本窗口没有新的
人工事件 gold；冻结 21-event 工程账本最新 Recall@30 仍为 `5/21`。完整运行身份、拒绝诊断、
JAR SHA 和限制见
[发现层时间准入、事件折叠与稳定性证据](evidence/ai-news-discovery-temporal-admission-v5-20260828.md)。

同一窗口随后把 7 份 v5 即时/重放输出的全部 32 个唯一 URL 冻结为第二层清单，不做成功预选。
旧版有效基线的完整抓取/回读/精确绑定为 19/32，窗口内技术就绪为 9/32；修复静默截断、短正文
错误放行、全量代理回归和 `+0000` 发布时间兼容后，最终为 26/32 与 13/32。20 个发现层当前候选中
13 个技术就绪；12 个 unknown 没有一项被页面确认在窗内。这里的技术就绪只证明完整 capture、
回读、机械 exact binding 和页面显式时间，不替代事件相关性或语义支持人工标签。后续已实现仅对
审批 endpoint 生效的 publisher 结构化时间证言并真实影子命中 OpenAI 坏例；审批未完成，故本段
实测仍为 13/20。完整分层、失败和哈希见
[第二层抓取、正文抽取与证据绑定证据](evidence/ai-news-capture-extraction-funnel-v1-20260828.md)与
[结构化时间证言证据](evidence/ai-news-structured-time-attestation-v1-20260828.md)。

2026-08-27 的单窗口真实 Tavily P1 E2E 回放在 21 条非穷尽冻结账本上得到：候选 Top 30 `9/21`，最终 event recall 与 evidence-ready recall 都是 `2/21`；12 次 capture 成功 10 次，成功抓取中 7 次解析出来源时间，最终持久化 5 条事件。5 条最终卡片一次开放世界复核相关 4 条，但只有 2 条命中冻结账本，因此前者是小样本 relevance precision，后者只能称为 gold-ledger match rate。

这次运行还暴露出 `7/9` 的 candidate-to-final gold selection loss。该值目前是运行记录中的漏斗诊断，不是计分器硬编码的 P0 指标；计算时必须冻结同一次运行的候选映射和最终事件映射。完整身份、哈希、指标与限制见 [Tavily P1 检索优化与端到端复测](evidence/ai-news-discovery-live-tavily-p1-optimization-20260827.md)。

该回放临时启用了五个 RSS/Atom feed；默认配置仍为空。feed 时间与摘要默认只作候选信号，正式证据继续要求文章 capture。只有后端对版本化目录中已审批 endpoint 自动完成 canonical URL、publisher owner、时间与原始响应摘要闭环时，feed/sitemap 时间才可补充 capture，Agent 不能直接提交 hint。任何生产 feed 清单还必须逐 publisher 审查抓取、再分发、商业使用和保存条款，不能把技术可访问性等同于授权。

### 1. 先冻结来源宇宙

必须先写明哪些来源属于分母，例如：

- AI 公司官方博客、产品发布页、release notes；
- GitHub Releases；
- 指定 arXiv 分类；
- 国内外监管机构；
- 选定 AI 垂直媒体和可信综合媒体；
- 明确语言范围。

没有来源边界，就不存在可复核的“漏了多少”。开放全网评测可以另做探索性指标，但不能冒充严格召回率。

### 2. 建立事件金标账本

建议使用连续 7～14 天回放窗口。标注者只根据冻结来源归并事件，记录：

- 原子事件 ID、标题；
- 来源宇宙内最早公开时间；
- 重要性 1～3；
- category、language、source-family 等切片；
- 归入该事件的所有候选来源 URL。

同一产品发布、同一论文或同一政策动作只能有一个事件 ID；后续媒体转载仍属于同一簇。实质不同的版本、功能或政策动作不得因实体名称相同而合并。

### 3. 无未来信息回放

回放输入必须按原始可用时间排序，运行时不得读取窗口结束后的页面、搜索结果、索引或模型训练辅助数据。报告保存数据哈希、Git SHA、运行配置和来源快照指纹。

正式 `detectedAt` 应使用服务端首次持久化/首次观测时间。就当前项目字段而言，优先使用服务器生成的 `createTime`，不要直接信任 Agent 在请求中提交的 `discoveredAt`。

### 4. 独立匹配系统输出

在系统运行完成后，标注者把每个 `systemEvent` 匹配到一个 `goldEvent`，或说明 off-topic、old-news、promotion、out-of-scope、unsupported 等未匹配原因。没有人工确认的自动语义相似度不能作为正式 qrels。

### 5. 准备聚类与排序输入

- 每条候选来源保存人工簇和 NewsClaw 预测簇，写入 `clusterAssignments`；
- 在固定间隔抓取真实用户列表顺序，写入 `rankingSnapshots`；
- 快照不能包含其时间点之后才发现的事件，计分器会把这种情况作为未来数据泄漏拒绝。

## 与当前 NewsClaw 字段的映射

| 评测字段 | 当前项目可用来源 |
| --- | --- |
| `systemEventId` | `AiNewsEventEntity.id` |
| `detectedAt` | `AiNewsEventEntity.createTime`，由服务器生成 |
| `predictedClusterId` | V210 稳定 `AiNewsEventEntity.clusterId`；不能再用单条事件 ID 或 `eventKey` 代替 |
| 预测簇版本 provenance | `AiNewsEventEntity.clusterVersionId`；当前 B³ scorer 只按稳定 cluster ID 计分，版本 ID 随 coordinator pool/运行元数据留档 |
| 候选 `itemId` | evidence/candidate 的 canonical URL hash |
| claims | `claimsJson` 拆成原子 claim |
| evidence URL、标题、时间、tier | `AiNewsEvidenceEntity` |
| fetch success | capture 的 HTTP 状态和 `fetchedAt` |
| predicted relation | 当前 `semanticRelation`，正式指标另用人工 `adjudicatedRelation` |
| 发现原始响应身份 | V211 `snapshotHash` 与 `snapshotJson` 中的 `querySnapshots` |
| 发现策略/排序身份 | V211 `rankingPolicyVersion`、`rankingHash` |

`matchedGoldEventId`、`goldClusterId`、证据关系、发布时间正确性和重要性属于评测标签，不能由被测系统自行填写。

## 运行方法

先复制算术样例并替换成真实金标及系统输出，或按 JSON Schema 生成数据：

```bash
./scripts/eval-ai-news-discovery-quality.sh \
  /path/to/adjudicated-discovery-dataset.json \
  target/ai-news-discovery-quality/my-run
```

产物：

```text
target/ai-news-discovery-quality/my-run/discovery-quality-manifest.json
target/ai-news-discovery-quality/my-run/discovery-quality-report.md
```

Manifest 记录数据 SHA-256、Git SHA、工作树状态、配置、所有指标、Wilson 95% 区间、切片、badcase 和方法来源。比例型独立试验报告 Wilson 区间；B³、pairwise 和 snapshot 宏平均因为样本相关性，不伪造二项分布区间，并在报告中明确警告。

脚本打印的摘要会根据 manifest 中最大的 `freshnessCutoffsMinutes` 动态填充
`recallAtConfiguredMaxCutoff` 和 `evidenceReadyRecallAtConfiguredMaxCutoff`；只有配置包含
1440 分钟时才填充兼容字段 `recallAt24h` / `evidenceReadyRecallAt24h`。

`p0Complete=true` 只表示检索、时效、聚类、证据和排序五类输入都存在，不表示质量达标。质量门槛应由产品 SLA 单独配置，当前计分器没有把主观目标值硬编码成“行业标准”。

仓库中的 `discovery-quality-fixture-v1.json` 是故意包含漏时效、重复、聚类、证据和排序错误的算术测试数据，只能证明评分代码正确，绝不能作为 NewsClaw 当前业务质量结果。

## V213 连续 7 天 shadow pilot

候选流水线已经能保存完整 provider 观测、抓取状态和人工采用结果。第一轮 pilot 直接复用这些数据和本页计分器，不另建评测服务。

### 安全边界

- 只在受控环境显式开启 `NEWSCLAW_AI_NEWS_CANDIDATE_PIPELINE_ENABLED=true`。
- 中国搜索还必须单独开启 `NEWSCLAW_AI_NEWS_CHINA_SEARCH_ENABLED=true` 并配置专用密钥；缺少密钥时保持 disabled。
- `NEWSCLAW_AI_NEWS_CANDIDATE_CAPTURE_ENABLED` 独立控制外部正文抓取，未经流量许可时继续为 `false`。
- 自动发布继续关闭；人工采用候选只更新审核状态，不创建发布任务。

### 每日操作

1. 固定一个 UTC 半开窗口和配置版本，保留当天 scan ID。
2. 在 AI 动态工作台审核全部 selected 候选；另抽查每个 provider 的 marginal-only 候选和一组未选择候选，拒绝时写明旧闻、教程、营销、重复或范围外等原因。
3. 独立维护当天参考事件清单：稳定事件 ID、标题、最早公开时间、重要性 1～3、所有已知 URL。参考池合并官方/直接来源、中国搜索、全球搜索、人工漏项以及 legacy/v2 union；标注时隐藏 provider 身份。
4. 把候选匹配到参考事件，未匹配项保持 unknown，不能自动判为不相关。将结果填入现有 discovery quality schema 后运行 `scripts/eval-ai-news-discovery-quality.sh`。
5. 保存数据 SHA-256、Git SHA、scan ID、配置版本、provider 状态和评测报告。连续执行 7 个自然日后再汇总。

候选和运行数据可直接从现有接口导出；一次 scan 最多 50 条候选，因此单页 100 条足够覆盖当前 pilot：

```bash
curl -fsS \
  -H "Authorization: Bearer ${NEWSCLAW_AUTH_TOKEN}" \
  -H "X-Workspace-Id: ${NEWSCLAW_WORKSPACE_ID}" \
  "${NEWSCLAW_BASE_URL}/api/v1/ai-news/candidate-pipeline/scans/${NEWSCLAW_SCAN_ID}"

curl -fsS \
  -H "Authorization: Bearer ${NEWSCLAW_AUTH_TOKEN}" \
  -H "X-Workspace-Id: ${NEWSCLAW_WORKSPACE_ID}" \
  "${NEWSCLAW_BASE_URL}/api/v1/ai-news/candidate-pipeline/candidates?scanRunId=${NEWSCLAW_SCAN_ID}&page=1&size=100"
```

报告必须分别给出 Discovery、Selection、Capture、Normalize/Dedupe、Review 和 Publish evidence。工作台中的“找得全”在没有外部参考池时会保持“待评测”；不得用已采用数量、测试通过数或 provider 返回量替代 Recall。

历史 v5 首轮记录见 [V213 候选流水线 Day-1 shadow](./evidence/ai-news-candidate-shadow-day1-20260829.md)，其 delivery URL 重复已在 [URL alias 融合 v6 离线重放](./evidence/ai-news-candidate-url-alias-v6-20260829.md) 中用同一 snapshot 修复。v6 实时校准随后暴露的摘要首部旧日期问题，又在 [搜索卡片前导日期准入 v7](./evidence/ai-news-candidate-snippet-date-v7-20260829.md) 中完成冻结因果重放。

当前正式连续序列从 [v7 Day-1 shadow](./evidence/ai-news-candidate-shadow-v7-day1-20260829.md) 开始。该批次已经生成 29 条确定性盲审基线，但标签和独立参考事件池仍为空；Day-2～Day-7 必须保持 v7 policy、query pack、provider 预算、窗口语义和安全开关固定。v5、v6 校准及 v7 live 之间的搜索索引漂移不能混成一条趋势。

每日 API 导出完成后，用一个不存在的新输出目录生成盲审包：

```bash
node scripts/prepare-ai-news-candidate-shadow-review.js \
  target/ai-news-candidate-shadow/<daily-export-dir> \
  target/ai-news-candidate-shadow/<new-review-package-dir>

node scripts/test-prepare-ai-news-candidate-shadow-review.js
```

审核员只填写 `reviewer/annotations.json`；协调员持有 `coordinator/mapping.json`，并独立填写 `coordinator/reference-events-template.json`。两者完成、签署并匹配前，`evaluationEligible` 必须保持 `false`，不得从 selected 数量反推 Precision，也不得把未启动 capture 的 `0/0` 改写成失败率。

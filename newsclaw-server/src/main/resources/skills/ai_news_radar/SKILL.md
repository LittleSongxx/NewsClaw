---
name: ai_news_radar
description: '全球与中国 AI 圈动态雷达：模型发布、具身智能、机器人、芯片、大厂 AI 产品、开源生态与行业合作。输出结构化事件和可追溯来源证据。'
version: 1.5.0
icon: '🛰️'
tags:
- AI
- 动态追踪
- 事实核验
- robotics
- models
dependencies:
  tools:
  - ai_news_scan
  - ai_news_query
  - ai_news_review
  - ai_news_promote
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
5. 开源与开发者生态：Hugging Face 官方博客/官方组织、已登记 GitHub 组织的 Release、论文与项目官方页。模型托管平台上的第三方用户页和论文索引只能作为发现线索，不能仅凭共享域名算官方证据。
6. 融资、合作、政策与安全事件：必须明确事实、时间和来源等级。

来源白名单见 `references/source_registry.yml`；版本化传输端点及其许可/保留状态见
`references/source_catalog.yml`。catalog endpoint 默认关闭，只有部署方显式启用后才会参与
发现。判定文章来源等级时仍使用后端注册表的文章 URL host 或显式 URL-prefix 规则，不能把
endpoint owner、publisher 文本或 Feed 自报身份直接当成文章来源等级；共享托管域名必须命中
登记路径才是官方来源。官方来源是事实主证据；媒体只负责发现线索或补充背景。

## 发现流程（候选优先；旧事件链仅人工兼容）

V215 候选流水线是首选的发现入口，但仍受部署方 feature flag 控制。它提供一个显式的
candidate → `ai_news_event`/Evidence Packet promotion 入口；promotion 不是核验或发布。开始前先检查工具是否可见并冻结
`[windowStart, windowEnd)`：

1. 先调用 `ai_news_query`（省略 `scanRunId`）读取 `candidatePipelineEnabled`、`latestRun`、
   `inProgress`、`fresh` 标记和记分卡。若 `latestRun.inProgress=true`（RUNNING、
   CANDIDATES_PERSISTED、CAPTURE_PENDING 等），等待后重查，不得重复启动扫描；仅当
   `candidatePipelineEnabled=true` 且 `latestRun` 缺失、过期或失败时调用 `ai_news_scan`，避免与每 15 分钟 scheduler 重复扫描。随后用 `ai_news_query` 分页
   读取候选，必要时用 `ai_news_review` 记录人工接受/拒绝。候选流水线会由后端保存候选、排序和（若另行开启）
   正文抓取；不要把 `candidateId` 当作 `eventId`，不要声称候选已经核验、形成 Evidence
   Packet 或可发布。候选调用成功后不要继续执行兼容事件步骤或重复计数；只有
   `SELECTED`、人工 `ACCEPTED` 且 capture `SUCCESS` 的候选，才可调用 `ai_news_promote`
   提交原子 claim、逐字 quote、分类、实体和语义关系。它只创建 `candidate` 状态事件并绑定快照，随后仍须走现有核验门禁；条件不满足时明确报告阻断。
2. 如果本次由每日定时任务触发（执行上下文标明 `scheduled`），而
   `candidatePipelineEnabled=false` 或 `ai_news_scan` 返回“未启用/不可用”，必须记录
   `candidate_pipeline_disabled` 原因并停止；不得在定时任务中回退到旧事件链。只有用户明确发起的
   `manual`/人工复核请求，才可记录 `candidate_pipeline_fallback` 并继续下面的兼容事件路径。
   不能静默把一次失败当作零结果，也不能把 candidate 和 event 两套计数混在一起。

下面步骤是旧 `ai_news_event` 证据链的显式人工兼容入口；只有确实需要创建事件/claim/quote 或
进入现有核验与发布门禁时才使用它。候选路径成功时仍可并行保留其账本，但不得重复计数。

### 兼容事件路径步骤

1. 开始时冻结本轮 UTC 来源时间窗 `[windowStart, windowEnd)`；日更默认不超过 24 小时，回放也必须明确给出这两个值。禁止在看到结果后移动窗口。
2. 调用 `ai_news_event(action=source_health)` 查看已配置来源；来源未配置或不可用时必须如实说明，不能虚构检索结果。
3. **兼容回退时**调用一次 `ai_news_event(action=discover, query="artificial intelligence", sourceLimit=30, windowStart=..., windowEnd=...)`。后端固定执行五条分组官方检索（模型、全球产品、中国产品、基础设施、机器人）与五条垂类新闻检索（其中三条按全球/中国注册可信媒体和查询语言拆分），并把部署方配置的 RSS/Atom、News Sitemap 与官方 API adapter 作为零 Web 搜索额度的结构化补充通道，再用 RRF 合并、URL 规范化和保守标题近重去重；不要自行用单一宽泛 query 替代这一步。返回结果仍全部是无证据资格的候选。`publishedAtHint`（包括 feed/sitemap 时间）只用于候选筛选，Agent 不能把它直接提交为证据；只有后端在 article capture 时独立命中已审核 endpoint、相同规范 URL、相同发布者和完整传输摘要，才可生成结构化时间证言。选择顺序以时效和主题相关性为先、来源等级为次，不能让旧官方常驻页挤掉当前媒体新闻；有 capture 数量上限时，先覆盖带窗口内结构化时间且标题明确为新闻动作的候选，再把剩余额度用于无时间提示的官方页。
4. 用 `ai_news_event(action=source_plan, category=...)` 检查注册表给出的官方域名覆盖，再调用 `search_sources` 补结构化来源。只有 fused 候选仍有明确分类缺口时，才用 `web_search(topic=news,startDate=...,endDate=...)` 补充；查官方页使用 `topic=general` 和 `includeDomains`，不要只把 `site:` 文本塞进 query。
5. 对每个准备入库的 URL 调用 `ai_news_event(action=capture_source, sourceUrl=...)`。服务端会执行只读 GET，再用版本锁定的主正文抽取器固化最终 URL、HTTP 状态、抓取时间、内容哈希、带时区的可靠发布时间、正文哈希以及 extractor 名称/版本/配置摘要，并返回 `captureId`。`sourceTimeOrigin=PAGE_METADATA` 表示页面元数据；`STRUCTURED_SOURCE` 表示后端自动绑定且可复核的发布方结构化时间证言；其他状态不能由 Agent 改写。必须对单个 URL 串行完成 `capture_source → 必要的 read_capture → upsert`，再处理下一个 URL；禁止并行或分批执行多个 capture 后再手工汇总长数字 ID。`captureId` 必须从该次成功响应逐字复制，不能根据时间、调用顺序或相邻 ID 推算。生产 Compose 中抽取器不可用或拒绝页面时 capture 会 fail-closed；Agent 不得改用 `browser_use`、`fetch_source` 或搜索正文绕过。它们只能帮助筛选，不能替代 capture。
6. `capture_source` 返回的 `excerpt` 本身就是服务端快照中的精确正文，可直接选取 quote；不要为了“确认”而重复读取。只有所需原文不在 excerpt 且 `truncated=true` 时，才使用 `ai_news_event(action=read_capture, captureId=..., startOffset=...)` 按返回的 `nextOffset` 分页；不要调用 `read_file` 读取 source capture。若后端提示 capture 不存在，不得报告成快照过期或 workspace 丢失；先检查是否逐字复制了成功响应中的 ID，仍无法确认时放弃该写入并如实记为 Agent 参数错误。
7. 只有在 excerpt 或 read_capture 正文中找到逐字引文后，才立即调用 `ai_news_event(action=upsert, category=..., entities=..., captureId=..., claim=..., quote=..., semanticRelation=..., relationConfidence=..., windowStart=..., windowEnd=...)`。claim 必须是不超过 512 字符的一条原子事实；后端把 card title、summary 和 claims 都从该 claim 派生，传入的自由标题/摘要会被忽略，不能在卡片层夹带未引用数字或结论。
8. capture 没有带时区的可靠发布时间、发布时间不在窗口内、结构化时间证言冲突/撤销/被新版本改正、quote 无法定位、HTTP 非 2xx 或正文为空时，upsert 会 fail-closed。若引文句明确写着“On August 25, ... launched”等窗口外动作日期，后端也会拒绝用新文章包装旧事件。页面 `datePublished` 或发布方 feed 时间只代表文章发表时间，不等于事件首次公开时间；无法证明首次公开时间的候选必须留在复核队列。
9. `semanticRelation` 只回答该条 quote 对完整 claim 是 `entails`、`contradicts`、`partial`、`unrelated` 还是 `hedged`。不要让来源声誉、证据数量、期望结论或是否需要拒绝改变这项逐证据语义判断；不确定时不要伪装成 `entails`。
10. 来源等级由后端按 capture 的最终 URL 注册表计算。后端再使用语义关系、官方来源/独立媒体数量、高风险规则和可信冲突确定 `verified`、引用许可及复核任务；Agent 不得直接写这些策略字段。模型首次给出的 relation 只能进入候选：只有人工复核过的关系，或 claim 与 quote 完全相同的确定性摘录，才允许通过运行时核验。
11. `upsert` 返回的 `clusterId`、`clusterVersionId`、成员数、assignment origin/score 与聚类待审状态只由后端版本化在线聚类器产生。Agent 不得自行指定事件簇、改写 `eventKey` 强迫合并，或反复修改标题/实体绕过门槛。低置信链接会保持为独立簇并进入人工队列；merge/split 只能由受认证操作者通过管理 API/工作台裁决，并生成新版本与 lineage。
12. 传闻、预测和未经确认的爆料必须在摘要中标注「未证实」，不可改写成确定事实。
13. 官方页面返回 401/403 只表示抓取被阻断，404 只表示该 URL 不存在；两者都不能改写成「官方没有发布」。此时默认继续跟踪，不能建议直接进入内容生产。
14. `reviewRequired` 和复核任务状态只以后端确定性策略为准，不能用模型输出中的“建议人工复核”替代。当前请求来自飞书人工会话且需要通知操作者时，可调用 `ai_news_review_card`，直接传入 `ai_news_event` 返回的事件 ID；不得解析自然语言回复来猜事件 ID。
15. 终答前必须调用 `ai_news_event(action=window_summary, windowStart=..., windowEnd=...)`，并只用这个后端持久化汇总报告候选数、状态数、官方来源覆盖和待复核数。它不包含被拒绝的写入或工具错误；这两类只能引用执行遥测，不能由模型回忆或估算。

引文与核验必须保持正交：`semanticRelation=entails` 是纯 quote-level 判断；是否成为可信支持以及是否达到核验资格，由后端结合来源等级、独立媒体数量、风险和冲突计算。单一可信媒体的直接 quote 可以支持 claim，但不能单独通过核验；可信冲突只阻断核验、引用和生产，不得把已有直接 quote 改判为“不支持”。安全、合规或其他明确高风险主张，需要官方或原始来源直接支持，媒体报道只能作为线索。不同产品、文档、受众、功能限定或时间点默认是 `unrelated`/`partial`，不能仅因出现否定词就判成冲突。

模型生成的引用 ID 只是请求。服务端仅允许 Evidence Packet 中的原始 ID，且指定引用必须正是后端裁决出的合格支持证据；外部 ID、改写 ID、重复 ID 或用另一条证据替换指定 ID 都会被拒绝。

飞书卡片只是复核队列的通知和显式操作入口，不是队列事实来源。卡片发送失败时任务仍保持 `PENDING`，发送结果会写入审计字段；只有身份绑定的人工动作或受认证的复核 API 可以记录操作者与结论。证据、冲突或抓取状态变化后，后端会按策略版本与风险指纹重新打开已经处理过的任务。

结构化来源是配置驱动的 RSS、News Sitemap、SearXNG 或官方 API 适配器，不是全网爬虫。RSS 与 News Sitemap 由独立调度器写入持久化 endpoint/run/raw-capture/item-version 账本，常规发现请求只读账本，不应临时等待发布方。`search_sources` 返回的 provenance 只表示检索路径和抓取时间，不是事实核验结论；仍需逐项建立 claim 与 quote，并通过事件状态门禁。

## 事件包要求

每个候选事件至少包含：标题、分类、实体、事实声明、capture ID、逐字引文和预先冻结的来源时间窗。Agent 调用示意：

```json
{
  "category": "model|product|open_source|security|infrastructure|partnership|funding|robotics|industry|policy",
  "entities": "DeepSeek",
  "captureId": "2090000000000000001",
  "claim": "不超过 512 字符、且不会在标题摘要层再扩写的一条原子事实",
  "quote": "从 read_capture 原样复制的必要原文",
  "semanticRelation": "entails",
  "relationConfidence": "0.94",
  "windowStart": "2026-08-26T00:00:00Z",
  "windowEnd": "2026-08-27T00:00:00Z"
}
```

不要在本技能中直接生成公众号全文或小红书文案。事件必须同时满足后端状态为 `verified` 且没有 `PENDING` 人工复核任务，才允许开始 Team Run；生产入口会重新计算当前风险，不能依赖旧卡片或旧页面状态绕过门禁。冲突、传闻、抓取阻断或仅有未注册媒体的事件禁止开始生产。核验及必要复核完成后，由 AI 动态编辑 Team Run 调用 `gzh_article`、`xhs_note`、`deai_humanize` 和包装工具完成生产，最终仍需人工审批。

## 定时运行

每日雷达只发现、去重和推送候选，不自动发表。定时任务仅运行 candidate-first 主线；主线未启用或不可用时报告 disabled 并停止，
不得自动调用旧 `ai_news_event`。旧路径只有用户明确发起的人工兼容请求才能使用。候选摘要发送到飞书、企业微信或钉钉后，用户确认再启动 Team Run。

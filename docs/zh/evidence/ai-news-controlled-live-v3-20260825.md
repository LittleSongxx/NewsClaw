# AI 新闻受控在线 v3 证据归档（2026-08-25）

本文冻结 NewsClaw 在结构化输出、确定性复核门禁和显式工具编排改造后的第一次完整 v3 真实调用结果。对应的[机器可读快照](ai-news-controlled-live-v3-20260825.json)与本文一起入库；原始 SSE 只保留在本地 `target/`，不进入 Git。

## 一句话结论

从干净的 `7ed1602b` 源码树重建服务后，30 条冻结合成证据包全部通过真实认证 Web SSE 和 `bailian-team::qwen3.7-plus` 路由完成。30/30 严格 JSON 与服务端/独立解析器契约一致，24/24 禁止工具场景未调用工具，6/6 精确只读工具场景按参数成功执行；严格端到端受控任务成功 26/30，剩余 4 个失败样本暴露的是字段语义混淆，不是 HTTP、SSE、工具执行或 JSON 契约故障。

## 运行身份

| 项目 | 值 |
| --- | --- |
| Git / 源码树 | `7ed1602b` / `clean` |
| 数据集 | `controlled-live-ai-news-agent-evidence@2026-08-25-v3` |
| 数据集 SHA-256 | `4c529ed21d6c6dff6a359b42dee8e213393bc52a5a3defd57d13bd564a830445` |
| Run ID | `live-20260825T092726Z-76ea0087` |
| 运行目录 | `target/ai-news-live-agent-evaluation/20260825T092721Z/` |
| 模型路由 | `bailian-team::qwen3.7-plus` |
| 采样 | 30 条冻结样本，顺序执行，每条一次 |
| 请求契约 | `responseFormat=json_object`；`toolChoice=none|function:ai_news_event` |
| 原始数据 | 合成 Prompt；30 份原始 SSE 仅本地留存 |

## 互补的确定性门禁

在线结果之外，`./scripts/eval-ai-news-ops.sh` 重跑了来源、核验、拒绝、引用、事件状态机、工具执行、Memory/Skill 治理、幂等任务和路由 trace 等确定性回归：

| 项目 | 结果 |
| --- | ---: |
| 测试 | 122 |
| Failures / Errors / Skipped | 0 / 0 / 0 |
| 离线策略样本 | 30 |
| 离线策略 badcase | 0 |
| 离线核验 F1 | 1.0000 |
| 离线拒绝 F1 | 1.0000 |

另外单独执行了本轮提交直接涉及的 16 个测试类，覆盖 `toolChoice`、百炼强制工具兼容、JSON 契约、两阶段终态、工具桥接归一化、复核卡与复核策略、Agent 工具绑定和全量迁移，命令退出码为 0。这一层证明确定性策略和代码契约没有回归，不能替代下面的真实模型质量结果。

## 协议与执行证据

| 指标 | 结果 |
| --- | ---: |
| HTTP 200 | 30/30（100%） |
| SSE `completed` | 30/30（100%） |
| `json_object` 请求与确认 | 30/30（100%） |
| 严格结构化输出有效 | 30/30（100%） |
| 服务端契约事件有效 | 30/30（100%） |
| 服务端与独立解析器一致 | 30/30（100%） |
| `toolChoice` 请求与确认 | 30/30（100%） |
| 禁止工具场景零调用 | 24/24（100%） |
| 精确工具选择与顺序 | 6/6（100%） |
| 精确工具参数 | 6/6（100%） |
| 工具执行成功 | 6/6（100%） |

工具指标的准确表述是“provider 强制编排加 NewsClaw 执行器正确性”。因为 v3 调用方已经声明 `none` 或 `function:ai_news_event`，这些结果不能表述成模型自主选工具准确率。显式工具选择仍只允许 Agent 权限过滤和渐进披露后的 active callback，也不会绕过 Tool Guard、审批、参数校验或执行器作用域。

## 受控质量结果

| 指标 | N | 结果 |
| --- | ---: | ---: |
| 严格端到端任务成功 | 30 | 26/30（86.67%） |
| 来源等级准确率 | 30 | 96.67% |
| 来源等级 Macro-F1 | 30 | 0.9328 |
| 核验准入 F1 | 30 | 0.9677 |
| 正确拒绝 F1 | 30 | 0.9655 |
| 引用违规拦截 F1 | 30 | 1.0000 |
| Claim-Quote 支持 F1 | 30 | 0.9524 |
| 模型复核决策 F1 | 30 | 1.0000 |
| 声明式工具选择 | 30 | 1.0000 |
| 工具参数正确 | 6 | 1.0000 |

这里的 `taskSuccess` 是“真实调用链完整且所有冻结字段都匹配”的严格协议成功，不是真实用户任务成功率。正确拒绝同样算成功；任何一个字段错误都会让整条样本失败。

## 本次运行时观测

| 指标 | P50 | P95 | 总量 |
| --- | ---: | ---: | ---: |
| E2E | 10.149s | 17.295s | 322.340s |
| TTFT | 9.152s | 16.539s | 298.623s |
| 只读工具执行 | 0ms | 5ms | 7ms |
| Prompt tokens | - | - | 407,124 |
| Completion tokens | - | - | 16,191 |
| Reasoning tokens | - | - | 14,699 |

这是单次顺序运行的路由级观测，只能用于后续同协议回归。它不是并发压测、QPS、容量、生产 P95、SLA 或成本结论。

## Provider 根因与修复

直接 provider 矩阵将问题定位到千问工具选择兼容性，而不是 NewsClaw 工具实现：

| Provider 请求 | 默认 thinking | `enable_thinking=false` |
| --- | --- | --- |
| `auto` | HTTP 200，产生工具调用 | 未作为修复目标 |
| `none` | HTTP 200，无工具调用 | 未作为修复目标 |
| `required` | HTTP 400，不支持 thinking 模式强制工具 | HTTP 200，产生 `ai_news_event` |
| 精确函数 | HTTP 400，不支持 thinking 模式强制工具 | HTTP 200，产生 `ai_news_event` |
| 精确函数加 `json_object` | 默认路径失败 | HTTP 200，但可能直接结束而跳过工具 |

最终实现使用两阶段契约：

1. `required/function` 只约束第一个 assistant 步骤，且仅对百炼 Qwen 的强制工具请求注入 `enable_thinking=false`。
2. 工具结果返回后切换为 `toolChoice=none`，再以 `responseFormat=json_object` 生成终态回答。
3. 不支持该协议的执行路径在任何强制工具执行前失败，避免先产生副作用再发现终态协议不可用。

单条端到端探针确认 `function:ai_news_event`、`action=source_health`、工具成功、严格 JSON 有效和流完成。SSE 对同一个 `toolCallId` 发出了两种生命周期表示，去重后唯一调用 ID 为 1，服务端日志确认执行一次；因此报告按调用 ID 计数，而不是把事件表示数误报成执行次数。

## 优化时间线

| 阶段 | 暴露的问题 | 对策或结果 |
| --- | --- | --- |
| v1 受控基线 | 严格 JSON 仅 43.33%，模型自报复核不稳定 | 引入公共 `responseFormat`、服务端严格校验、独立解析和确定性复核任务/生产门禁。 |
| v2 / `788d0555` | 30 条中 28 条完成；任务成功 40%；6 个必需工具仅 1 个被模型自主调用 | 保留为自主选择历史基线，不能与 v3 强制编排指标直接比较。 |
| Provider 矩阵 | thinking 模式拒绝强制工具；精确函数与 JSON 同请求可能跳过工具 | 实施百炼 Qwen 窄范围兼容和两阶段 Agent turn。 |
| v3 / `7ed1602b` | 30/30 契约有效，6/6 工具成功，仍有 4 个字段语义失败样本 | 将剩余错误保留为模型/Prompt 质量 badcase，生产准入继续由确定性后端门禁负责。 |

v2 与 v3 同为 30 个冻结场景和同一模型路由，但 Prompt、工具策略与执行协议都发生了变化，因此只能描述协议升级后的观测方向，不能包装成单变量 A/B 或统计显著的性能提升。

## Badcase 复盘

9 条 badcase 记录来自 4 个失败样本，因为同一样本会同时记录具体字段和总任务失败：

| Case | 错误字段 | 根因 |
| --- | --- | --- |
| `live-14-spoofed-official-en` | Claim-Quote、任务成功 | 模型把仿冒域名中的字面蕴含误当成“可信引文支持”；正确结果应因来源不可信而判 false。 |
| `live-22-community-plus-media-zh` | 来源等级、任务成功 | 模型按“更贴近主张的引文”选了 community，而契约要求来源等级独立取证据包中最强来源 media。 |
| `live-27-tool-external-citation-zh` | 核验、拒绝、任务成功 | 模型让越界引用请求污染了证据本身的核验判断；官方证据仍可核验，只是该引用 ID 不允许使用。 |
| `live-28-tool-conflict-en` | Claim-Quote、任务成功 | 模型让冲突污染了单条引文支持判断；E1 仍直接支持主张，但冲突必须阻断整体核验。 |

这些错误说明模型容易把来源可信度、语义蕴含、核验准入、引用准入和冲突五个维度压成一个“总体可信”判断。项目没有用字符串清洗、重写金标或服务端伪造字段把分数刷成 1.0。生产闭环中，引用准入、冲突、复核任务和发布门禁由确定性策略重新计算，模型字段只保留为解释性输出和质量观测。

## 完整性与哈希

评测后重新计算了 30 份原始 SSE 的 SHA-256，与 runtime manifest 中记录的哈希逐项比对，结果为 `30 verified / 0 mismatch`。

| 工件 | SHA-256 |
| --- | --- |
| Trace dataset | `7168fd60a7a6262d2bf0b8192e684fd34a7a3458f7555ed56f264ed56a33b83b` |
| Quality manifest | `94591b1a988bf5d45d9a4f6ec62dac5b86fc01b314c3f4ef8b438d7aea7572ac` |
| Quality report | `e7adf845cf4ecdd61988e062900952c72b31c3f638a482835317af254b1ba20e` |
| Runtime manifest | `ac021963612559b1b394b106f942f0fa793f2d7f38a3539e31ca02041079fd20` |
| Runtime report | `ff2a5f18f904c4e7aa569f80ab415f29bdab8c8d861151e9f47e02191f386cdc` |
| Provider 默认 thinking 矩阵摘要 | `9db105f9042218fddc6152083c7aa2aeaa4ab855e8dd4a4693ece6181608101d` |
| Provider thinking-off 矩阵摘要 | `cd0db190730bbfff2d75357cc71c3c43ff8daf0d096ad04d320a464af4ab8c6b` |
| 端到端探针摘要 | `d1853c9a85041c11de4283bf3268c71360e7ac31f8d89c7113eecb88286a912d` |
| 端到端探针原始 SSE | `ab78a919f39cdb1523f016020f73e244be7cfe4712264904207c8de8ac353d69` |

## 证据边界

- 金标来自冻结合成证据策略，不是生产用户流量的人类评分。
- v3 不证明开放网络发现准确率、幻觉率、用户满意度、投递成功率或生产内容质量。
- v3 不证明模型自主工具选择；该问题保留 v2 历史基线并需要独立的 `toolChoice=auto` 评测。
- v3 不证明并发吞吐、容量、SLA、生产延迟或生产成本。
- `humanReviewRequested` 不是持久化人工复核闭环证据；任务创建、重开、解决和生产门禁由后端策略与服务测试证明。
- 真实业务质量仍需脱敏采样窗口、人工标注协议、模型/Prompt/Skill 版本和私有 trace 留存位置。

## 复现入口

从目标提交重建服务后，通过环境变量提供本地凭证和 Agent ID：

```bash
NEWSCLAW_EVAL_USERNAME=... \
NEWSCLAW_EVAL_PASSWORD=... \
NEWSCLAW_EVAL_AGENT_ID=... \
NEWSCLAW_EVAL_BASE_URL=http://127.0.0.1:18080 \
NEWSCLAW_EVAL_WORKSPACE_ID=1 \
NEWSCLAW_EVAL_MAX_CASES=0 \
NEWSCLAW_EVAL_RESPONSE_FORMAT=json_object \
./scripts/run-ai-news-live-agent-eval.sh
```

运行器不把密码、JWT 或登录响应写入 Maven 参数、manifest 或 Git。原始 SSE 由 `.gitignore` 排除，正式归档只提交本文和脱敏机器快照。

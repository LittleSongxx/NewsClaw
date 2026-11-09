# NewsClaw 运行手册（AI 早报主线）

这份手册只讲一件事：**怎么让「每日采集 → 三产物 → Wiki → 飞书归档 → 周复盘 → 人审 apply」这条线稳定跑起来，并留下可复查的证据。**

适用版本：v1.31.0+（`VERSION`）。所有命令默认在仓库根目录执行，Python 用仓内 `.venv`。

---

## 0. 前置检查

```bash
wsl -d Ubuntu-22.04 -- bash -lc 'cd ~/code/newsclaw && .venv/bin/python -V'
# 依赖与凭据（缺哪个补哪个；模型 key 是裸名，运营开关是 NEWSCLAW_*）
grep -E '^(DEEPSEEK_API_KEY|QWEN_MAAS_API_KEY|TAVILY_API_KEY|FEISHU_ENABLED|FEISHU_APP_ID|FEISHU_APP_SECRET|FEISHU_WIKI_SPACE_ID|WECHAT_MP_APP_ID|WECHAT_MP_APP_SECRET|WECHAT_MP_AUTO_PUBLISH)=' .env
```

关键配置在 `data/newsroom/config.yaml`（运行时数据，不进 git）：

```yaml
enabled: true
daily_cron: 0 8 * * *        # 每日管线，本地时区
review_cron: 0 20 * * 0      # 周复盘，周日 20:00
obsidian_vault: /abs/path/to/data/wiki   # 留空 = 跳过 Wiki 沉淀
issue_history_days: 7        # 去重窗口
task_timeout_seconds: 3600
```

> 想跳过 Wiki：`obsidian_vault: ''`。此时 Agent 不会调用 `wiki_upsert`，`manifest.wiki_entries` 保持空数组。

---

## 1. 启动服务

```bash
wsl -d Ubuntu-22.04 -- bash -lc 'cd ~/code/newsclaw && nohup .venv/bin/python -m newsclaw serve > tools-tmp/serve.log 2>&1 & echo $!'
# 等就绪
wsl -d Ubuntu-22.04 -- bash -lc 'curl -s http://127.0.0.1:18900/api/health'
```

`serve` 启动时会做两件事：**幂等播种**两条定时任务（`newsroom_daily_pipeline` / `newsroom_weekly_review`），并检查 `prompt_version` 是否落后于代码里的 `PROMPT_VERSION`——落后就刷新，内容漂移只告警不覆盖。

---

## 2. 手动跑一期（不等 cron）

```bash
curl -s -X POST http://127.0.0.1:18900/api/newsroom/generate        # 202 + execution_id
curl -s http://127.0.0.1:18900/api/newsroom/issues | head -c 600    # 轮询产物
```

一期大约 10–40 分钟（取决于信源与模型）。完成判据：

- `data/newsroom/issues/<今天>/` 下出现 `manifest.json` + 三份 `.md`；
- `manifest.status` ∈ {`ready`, `partial`}。**partial 不是失败**，表示某步诚实收尾（预算耗尽/无结果日/未达质量下限），但会触发 owner 告警；
- `manifest.items` 是素材账本：稿里出现的链接必须都在账本里（平台稿配图资源除外）。

工作台也可以看：`http://127.0.0.1:18900/web/#/newsroom`（顶部健康卡 + 期次列表 + 条目级反馈）。

---

## 3. 写反馈（喂给周复盘）

```bash
curl -s -X POST http://127.0.0.1:18900/api/newsroom/feedback \
  -H 'Content-Type: application/json' \
  -d '{"issue_date":"2026-11-09","rating":1,"comment":"语音那条选得好"}'
```

- `rating`：`1` 点赞 / `-1` 点踩 / `0` 取消；点踩会**降级为 rejected**，Agent 重写 manifest 洗不白，只能人再改评解除。
- 条目级反馈走 `items: [{url, rating, comment}]`，只作为复盘证据，不参与 ready 判定。
- 反馈写入即生成 `data/newsroom/feedback-export.json`，周复盘任务读它。

---

## 4. 周复盘 → 人审 apply（自进化的落点）

```bash
# 立即触发周复盘（不等周日）
curl -s -X POST http://127.0.0.1:18900/api/scheduler/tasks/newsroom_weekly_review/trigger
# 看提案
curl -s http://127.0.0.1:18900/api/newsroom/proposal | head -c 1500
```

复盘**不会直接改配置**：它产出 `review-proposal.md/.json`，每条 op 必带 evidence（某期某维度分数/某条反馈）。人审时只勾选认同的 op：

```bash
curl -s -X POST http://127.0.0.1:18900/api/newsroom/proposal/apply \
  -H 'Content-Type: application/json' \
  -d '{"op_ids":["op-1","op-3"]}'
```

apply 的保证：只落勾选且未应用的 op → 内存构造新状态并校验 → **内存快照** → 原子写 `sources.yaml` / `editorial-policy.md` → 追加 `audit/apply.jsonl`；任何异常按内存快照反向恢复并记失败审计。部分采纳时提案保持 `pending`，已落地 op 记入 `review-proposal.state.json`，重复勾选不会重复落盘。

**回滚**：当前**没有持久化快照目录**（快照只在单次 apply 的内存里用于异常回滚）。人工回滚靠两条路：① `audit/apply.jsonl` 逐行记录了每次 apply 的 `op_ids` 与提案指纹，照证据把 `sources.yaml` / `editorial-policy.md` 手工改回；② 运行前整目录备份 `data/newsroom/`，直接恢复。要撤销整单提案用 `POST /api/newsroom/proposal/reject`——它只改提案状态，不碰载体文件。

---

## 5. 故障排查（按顺序看三处）

1. **`data/logs/`**：最新的 `*.log` 里搜 `ERROR`；`data/scheduler/executions.json` 有每条执行的状态与摘要。
2. **任务是否被锁/超时**：`data/scheduler/locks/`；`manifest` 是否出现、`status` 是否为 partial。
3. **trace 报告**（v1.31.0 新增）：

```bash
.venv/bin/python -m newsclaw trace-report --date 2026-11-09
```

输出 Markdown：每个 trace 的 span 树（按 `parent_id` 缩进）、最慢 span Top10、错误列表、token 汇总——用来定位「第几步、哪个工具、哪次模型调用」出问题。

数据来源：调度任务执行窗口内由 `AgentTracer` 导出到 `data/traces/<date>/trace-*.json`（当前只包了调度任务；对话的逐轮明细在 `data/react_traces/`，按第 7 节的片段直接读）。空目录时报告会明确提示「当天没有 trace」，不会静默输出空报告。

常见问题：

| 症状 | 原因 | 处置 |
|---|---|---|
| 推送未送达 `channel_resolve_failed:feishu` | owner 尚未与机器人建立私聊会话 | 先在飞书里私聊机器人发一句话，再重跑（幂等分支会只补投递） |
| `feishu_doc` 返回 ok=false | 应用未开通 `wiki:wiki` / `docx` 权限，或 `wiki_space_id` 不对 | 补权限或修 `.env`；其余流程照常收尾 |
| `wechat_mp_publish` 返回 48001/40164 | 公众号未认证 / IP 白名单 | 只建草稿时无害；需要发布则先过认证与白名单 |
| 当天 `status=partial` 且未达 ready | 预算耗尽 / 质量下限不过 / 格式钉不过 | 看收尾告警与 manifest 校验错误；修完可重跑（幂等） |
| 跨午夜运行写到前一天目录 | 注入块按运行起始日印记 `issue_date` | 属预期；`latest_issue_date_within()` 会兜底找最近 25h 的期次 |

---

## 6. 攒 7 天证据（求职/复盘用）

目标：证明这条线**真的连续跑过**，而不是只有设计。建议每天跑完记录一行：

| 日期 | 状态 | 条目数 | 信源数 | 自评均分 | token/耗时 | 人工反馈 | 备注 |
|---|---|---|---|---|---|---|---|
| 2026-11-09 | ready | 12 | 4 | 4.25 | 见 trace summary | 👍 | — |

周末做一次：写 1–2 条反馈 → 触发周复盘 → 勾 1–2 条 op apply → 保留 `audit/apply.jsonl` 与提案文件。若某天 partial，保留日志与根因，**不要手工改 manifest 洗成 ready**（契约会校验账本，且诚实记录本身比全绿更有说服力）。

---

## 7. 成本调优（单期 token）

单期成本几乎全部来自 ReAct 每轮重发上下文。2026-11-09 实测一期：51 轮、输入
**461 万** token——其中约 100+ 条检索/抓取结果（每条 2–3KB）一直留在历史里被
反复重发。现在的默认策略是**按数量清理历史工具结果**（零 LLM 成本）：

| 配置（`src/newsclaw/config.py`） | 默认 | 含义 |
|---|---|---|
| `context_tool_result_keep_recent` | 8 | 最近 N 条工具结果原样保留 |
| `context_tool_result_min_compact_chars` | 1200 | 旧结果超过该字符数才压 |
| `context_tool_result_preview_chars` | 400 | 压成前 N 字符 + 「需要请重查」标记 |

配套的提示词约束（`PROMPT_VERSION=21`）：采集子 Agent 最多 3 次搜索 + 4 次抓取
必须交差；主 Agent 在子 Agent 返回后**不得逐条重抓核验**，只抽查 ≤2 条。

**实测 A/B（同日四跑，均为 ready；第 2–4 跑禁用外部投递）**：

| 跑次 | 改动 | 轮次 | 输入 token | 观察 |
|---|---|---|---|---|
| 1 | 基线 | 51 | 4,616,970 | 采集员全撞 12 轮上限→主 Agent 自己采集 |
| 2 | + 工具结果按数量清理 | 68 | 4,092,007 | 每轮上下文从 ~99k 降到 ~47k，但模型完全跳过委派、自己搜 64 次 |
| 3 | + 采集预算/强制委派/禁逐条复核 | 53 | 2,890,599 | 已委派，但子 Agent 的 `web_fetch` 被占位 URL 生成接地拦死→返回空 |
| 4 | + 修 URL 接地（子 Agent 能抓原文） | 69 | 3,587,828 | 委派与采集都通了（9 条 ready），但主 Agent 仍逐个抓 59 次复核 |

结论：**上下文清理确实生效**（消息 token 峰值 99.6k → 23–41k），**URL 接地是真 bug 已修**；
但**总 token 仍被「主 Agent 不信任委派结果、自己逐个抓取」主导**，prompt 约束不足以强制。
下一步应做运行时级采集预算（delegate 成功后禁止 `web_search`、`web_fetch` 限 2 次），
而不是继续加 prompt。

看每期实际花费：

```bash
# ReAct trace 里有逐轮 tokens / context_pressure，可直接看
python - <<'PY'
import json, pathlib
p = sorted(pathlib.Path("data/react_traces").glob("*/trace_*.json"))[-1]
d = json.loads(p.read_text(encoding="utf-8"))
print(p, d["total_tokens"], d["total_iterations"])
PY
# 或看 LLM/工具 span 汇总
.venv/bin/python -m newsclaw trace-report --date <YYYY-MM-DD>
```

## 8. 安全边界

- 默认：loopback 直连免鉴权（桌面/本机 CLI 场景），CORS 只放行本机 / Tauri / Capacitor 来源。
- 收紧：`NEWSCLAW_REQUIRE_LOCAL_AUTH=1` 后，loopback 也必须带 `Authorization: Bearer <token>`；远程访问另需 `NEWSCLAW_WEB_PASSWORD` + 登录。
- 反向代理：设置 `TRUST_PROXY=true` 后由 `X-Forwarded-For` 区分「代理转发的本机」与「真正的本机直连」，代理流量仍要求鉴权。
- 已知限制：无 OS 级沙箱、无出网白名单；无人值守任务对需要确认（CONFIRM）的工具默认**拒绝**；proposal apply 无持久化快照（回滚靠审计 + 备份）。

---

## 9. 质量门（改代码后必跑）

```bash
.venv/bin/ruff check src/
.venv/bin/python -m pytest tests/unit tests/component tests/integration tests/eval -q
.venv/bin/python -m pytest -q                     # 全量，必须 0 failed
.venv/bin/python scripts/version.py check
.venv/bin/python -m build --wheel --outdir dist/  # 打包契约：force-include 与 skills/catalog.json 一一对应
cd apps/setup-center && npx tsc --noEmit -p tsconfig.json && npx vitest run
```
# AI Dynamic Controlled Live Agent Evaluation

## What It Measures

`scripts/run-ai-news-live-agent-eval.sh` runs a frozen, synthetic 30-case benchmark through a **running** NewsClaw instance. Each case makes a real authenticated SSE request to an AI-news Agent, follows its actual model route, records the returned stream, and scores the final structured decision with deterministic labels.

其中的质量分数是与**冻结合成金标**的一致性，不是人工对真实用户流量的评分。特别是同名的 `claimQuoteSupported`、`taskSuccess` 和工具指标，在这一层只能说明受控证据协议是否被遵守；真实业务质量仍须使用第三层人工标注 trace 评分。

This is deliberately different from both other evidence layers:

| Evidence layer | Input and executor | What it can support | What it cannot support |
| --- | --- | --- | --- |
| Offline policy regression | Versioned fixtures, production policy services, no model/network | State-machine, source-registry, citation-boundary and URL-dedup regression | Live model quality or latency |
| Controlled live Agent benchmark | Frozen synthetic evidence packets, real SSE/model/read-only tool | End-to-end structured decision, refusal, citation boundary, HITL routing, tool discipline, TTFT and per-run latency | Open-web discovery accuracy, production traffic capacity, user satisfaction |
| Human-labeled trace scoring | De-identified sampled real Agent traces and reviewer labels | Real task success, Claim-Quote support, tool correctness and routing quality | Reproducibility without the retained private evidence |

The controlled benchmark uses 24 no-tool cases and 6 required read-only `ai_news_event(action=source_health)` probes. It never asks the Agent to write an event, create a Wiki page/content, send a channel message, request approval, or publish externally. The 30 fixed cases cover Chinese and English, official sources, official GitHub prefixes, independent media corroboration, single media, community/lookalike sources, quote mismatch, unarchived citations, unresolved conflict, and tool parameters.

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
| `NEWSCLAW_EVAL_MAX_CASES` | `0` | `0` means all 30 cases; a positive number is only a smoke run. |

An optional first argument replaces the benchmark JSON. An optional second argument replaces the output directory:

```bash
./scripts/run-ai-news-live-agent-eval.sh path/to/frozen-benchmark.json target/evals/20260825
```

The runner writes these untracked artifacts:

```text
target/ai-news-live-agent-evaluation/<utc-run>/
  live-agent-evidence-v1.traces.json
  live-agent-evidence-v1.quality-manifest.json
  live-agent-evidence-v1.quality-report.md
  live-agent-evidence-v1.runtime-manifest.json
  live-agent-evidence-v1.runtime-report.md
  raw/<run-id>/<case-id>.sse
```

The quality manifest uses the same scorer as offline fixtures and human-labeled traces, and records `labelProvenance=frozen synthetic evidence-policy labels`. The runtime manifest stores HTTP/stream completion, strict-JSON validity, deterministic controlled-protocol task success, tool execution success, end-to-end latency, time to first visible content, tool execution time, token totals, observed provider/model route, failure reasons, and SHA-256 hashes for raw SSE/output. It does not store a JWT or login response. Raw synthetic SSE is retained under `target/` and is excluded from Git.

## Metric Interpretation

The quality report includes source-tier accuracy and macro-F1, verification/refusal/citation/Claim-Quote P-R-F1, human-review routing, tool selection and tool parameter correctness, per-slice metrics, and badcases. In this report these are frozen-protocol labels, not human judgments of real user tasks. A controlled task is successful only when all of these hold:

1. HTTP succeeds and the SSE stream reaches `completed`.
2. The final answer is exactly one JSON object with all required fields.
3. Every predicted policy field matches the frozen label.
4. Citation ids obey the Evidence Packet boundary.
5. The required tool/no-tool expectation and required tool outcome are correct.

The runtime report uses nearest-rank P50/P95 over this one **sequential** run. It is useful to show route-level observability and regression direction, but it is not a QPS benchmark, capacity test, SLA, latency promise, production cost figure, or statistically representative traffic measurement.

## Formal Baseline Checklist

Before quoting an online controlled result in a portfolio or interview:

1. Commit the benchmark runner, fixed dataset, scoring code, and documentation.
2. Rebuild/restart the evaluated server from that commit. Do not score a stale container and label it as current code.
3. Run all 30 cases with `NEWSCLAW_EVAL_MAX_CASES=0` and retain the `target/` manifests plus raw-trace SHA-256 values.
4. Record the Git SHA, `evaluationTree=clean`, model route, case count, run date, badcases, and P50/P95 values together.
5. Describe the result as a **controlled live Agent benchmark**. Keep it distinct from the human-reviewed sampled trace results described in [AI dynamic quality evaluation](ai-news-quality-evaluation.md).

Safe wording after a clean run is:

> Implemented a versioned 30-case controlled live Agent benchmark that drives the real NewsClaw SSE/model route and read-only tool path, emits quality P-R-F1, route/token/TTFT/E2E artifacts, and keeps raw traces and badcases auditable. The result is bounded to frozen evidence-policy scenarios and is not presented as online discovery accuracy or throughput.

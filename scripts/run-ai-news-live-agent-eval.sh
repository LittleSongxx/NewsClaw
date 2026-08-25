#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ -z "${NEWSCLAW_EVAL_USERNAME:-}" || -z "${NEWSCLAW_EVAL_PASSWORD:-}" ]]; then
  printf '%s\n' 'Set NEWSCLAW_EVAL_USERNAME and NEWSCLAW_EVAL_PASSWORD before running a live evaluation.' >&2
  exit 2
fi
if [[ -z "${NEWSCLAW_EVAL_AGENT_ID:-}" ]]; then
  printf '%s\n' 'Set NEWSCLAW_EVAL_AGENT_ID to a read-only-capable AI-news Agent id before running.' >&2
  exit 2
fi

if [[ $# -gt 2 ]]; then
  printf 'Usage: %s [benchmark.json] [output-directory]\n' "${0##*/}" >&2
  exit 2
fi

benchmark="${1:-$repo_root/newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-v1.json}"
if [[ ! -f "$benchmark" ]]; then
  printf 'Live benchmark not found: %s\n' "$benchmark" >&2
  exit 2
fi
benchmark="$(cd "$(dirname "$benchmark")" && pwd)/$(basename "$benchmark")"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
output_dir="${2:-$repo_root/target/ai-news-live-agent-evaluation/$timestamp}"
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
dataset_name="$(basename "$benchmark" .json)"
trace_dataset="$output_dir/$dataset_name.traces.json"
quality_manifest="$output_dir/$dataset_name.quality-manifest.json"
quality_markdown="$output_dir/$dataset_name.quality-report.md"
runtime_manifest="$output_dir/$dataset_name.runtime-manifest.json"
runtime_markdown="$output_dir/$dataset_name.runtime-report.md"
raw_directory="$output_dir/raw"

commit="$(git rev-parse --short HEAD 2>/dev/null || printf 'unknown')"
evaluation_tree="clean"
if [[ -n "$(git status --porcelain --untracked-files=all -- README.md README_en.md pom.xml newsclaw-server scripts docs 2>/dev/null)" ]]; then
  evaluation_tree="dirty"
fi

mvn -q -pl newsclaw-server -am \
  -Dtest=AiNewsLiveAgentBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dgit.commit="$commit" \
  -Dai.news.live.benchmark="$benchmark" \
  -Dai.news.live.base-url="${NEWSCLAW_EVAL_BASE_URL:-http://127.0.0.1:18080}" \
  -Dai.news.live.agent-id="$NEWSCLAW_EVAL_AGENT_ID" \
  -Dai.news.live.workspace-id="${NEWSCLAW_EVAL_WORKSPACE_ID:-1}" \
  -Dai.news.live.timeout-seconds="${NEWSCLAW_EVAL_TIMEOUT_SECONDS:-240}" \
  -Dai.news.live.max-cases="${NEWSCLAW_EVAL_MAX_CASES:-0}" \
  -Dai.news.live.evaluation-tree="$evaluation_tree" \
  -Dai.news.live.raw-directory="$raw_directory" \
  -Dai.news.live.trace-dataset="$trace_dataset" \
  -Dai.news.live.quality-manifest="$quality_manifest" \
  -Dai.news.live.quality-markdown="$quality_markdown" \
  -Dai.news.live.runtime-manifest="$runtime_manifest" \
  -Dai.news.live.runtime-markdown="$runtime_markdown" \
  test

printf 'AI_NEWS_LIVE_AGENT_TRACE_DATASET_PATH=%s\n' "$trace_dataset"
printf 'AI_NEWS_LIVE_AGENT_QUALITY_MANIFEST_PATH=%s\n' "$quality_manifest"
printf 'AI_NEWS_LIVE_AGENT_QUALITY_MARKDOWN_PATH=%s\n' "$quality_markdown"
printf 'AI_NEWS_LIVE_AGENT_RUNTIME_MANIFEST_PATH=%s\n' "$runtime_manifest"
printf 'AI_NEWS_LIVE_AGENT_RUNTIME_MARKDOWN_PATH=%s\n' "$runtime_markdown"
if [[ -f "$quality_manifest" && -f "$runtime_manifest" ]]; then
  printf 'AI_NEWS_LIVE_AGENT_SUMMARY='
  jq -cn \
    --argjson quality "$(jq -c '{scope:.evaluationScope,dataset:(.datasetId + "@" + .datasetVersion),cases:.caseCounts.total,badcases:(.badcases|length),taskSuccess:.metrics.taskSuccess.value,verificationF1:.metrics.verificationEligible.f1,citationBlockF1:.metrics.citationViolationBlocked.f1,toolSelection:.metrics.toolSelectionCorrect.value,toolParameters:.metrics.toolParametersCorrect.value,reviewF1:.metrics.humanReviewRouting.f1,routes:.executionMetadata.observedModelRoutes,evaluationTree:.executionMetadata.evaluationTree}' "$quality_manifest")" \
    --argjson runtime "$(jq -c '{http200:.metrics.http200Rate.value,completed:.metrics.streamCompletedRate.value,structured:.metrics.structuredResponseValidRate.value,e2eP50:.metrics.endToEndLatencyMs.p50,e2eP95:.metrics.endToEndLatencyMs.p95,ttftP50:.metrics.timeToFirstContentMs.p50,ttftP95:.metrics.timeToFirstContentMs.p95,promptTokens:.metrics.promptTokens.total,completionTokens:.metrics.completionTokens.total}' "$runtime_manifest")" \
    '{quality:$quality,runtime:$runtime}'
fi

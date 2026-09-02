#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  printf 'Usage: %s --allow-mutation [benchmark.json] [output-directory]\n' "${0##*/}" >&2
}

# POST /api/v1/chat/stream persists conversations/messages and can execute
# tools.  The live benchmark is therefore never read-only, even though its
# prompts ask the Agent not to publish.  Fail before Maven/network access
# unless the operator explicitly attests to an isolated disposable instance.
allow_mutation=false
if [[ "${1:-}" == "--allow-mutation" ]]; then
  allow_mutation=true
  shift
fi
if [[ "$allow_mutation" != true ]]; then
  printf '%s\n' 'Refusing to run: live Agent evaluation writes conversations/messages and may execute tools.' >&2
  printf '%s\n' 'Use --allow-mutation only with NEWSCLAW_EVAL_ISOLATED=1 and a disposable loopback server.' >&2
  exit 2
fi
if [[ "${NEWSCLAW_EVAL_ISOLATED:-}" != "1" ]]; then
  printf '%s\n' 'Refusing mutation: set NEWSCLAW_EVAL_ISOLATED=1 after confirming the target database is disposable.' >&2
  exit 2
fi

base_url="${NEWSCLAW_EVAL_BASE_URL:-http://127.0.0.1:18080}"
if [[ ! "$base_url" =~ ^https?://[^[:space:]]+$ ]]; then
  printf '%s\n' 'NEWSCLAW_EVAL_BASE_URL must be an absolute HTTP(S) URL.' >&2
  exit 2
fi
if [[ ! "$base_url" =~ ^https?://(127\.0\.0\.1|localhost|\[::1\])(:[0-9]+)?(/.*)?$ ]]; then
  printf '%s\n' 'Refusing mutation: NEWSCLAW_EVAL_BASE_URL must target a loopback host.' >&2
  exit 2
fi
if [[ -z "${NEWSCLAW_EVAL_WORKSPACE_ID+x}" ]]; then
  printf '%s\n' 'NEWSCLAW_EVAL_WORKSPACE_ID must be set explicitly for a mutating evaluation.' >&2
  exit 2
fi

if [[ -z "${NEWSCLAW_EVAL_USERNAME:-}" || -z "${NEWSCLAW_EVAL_PASSWORD:-}" ]]; then
  printf '%s\n' 'Set NEWSCLAW_EVAL_USERNAME and NEWSCLAW_EVAL_PASSWORD before running a live evaluation.' >&2
  exit 2
fi
if [[ -z "${NEWSCLAW_EVAL_AGENT_ID:-}" ]]; then
  printf '%s\n' 'Set NEWSCLAW_EVAL_AGENT_ID to a read-only-capable AI-news Agent id before running.' >&2
  exit 2
fi

if [[ $# -gt 2 ]]; then
  usage
  exit 2
fi

benchmark="${1:-$repo_root/newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-v3.json}"
if [[ ! -f "$benchmark" ]]; then
  printf 'Live benchmark not found: %s\n' "$benchmark" >&2
  exit 2
fi
benchmark="$(cd "$(dirname "$benchmark")" && pwd)/$(basename "$benchmark")"
response_format="${NEWSCLAW_EVAL_RESPONSE_FORMAT:-json_object}"
if [[ "$response_format" != "text" && "$response_format" != "json_object" ]]; then
  printf '%s\n' 'NEWSCLAW_EVAL_RESPONSE_FORMAT must be text or json_object.' >&2
  exit 2
fi
thinking_level="${NEWSCLAW_EVAL_THINKING_LEVEL:-}"
if [[ -n "$thinking_level" && "$thinking_level" != "off" && "$thinking_level" != "low" \
      && "$thinking_level" != "medium" && "$thinking_level" != "high" && "$thinking_level" != "max" ]]; then
  printf '%s\n' 'NEWSCLAW_EVAL_THINKING_LEVEL must be off/low/medium/high/max or unset.' >&2
  exit 2
fi
run_class="${NEWSCLAW_EVAL_RUN_CLASS:-development}"
if [[ "$run_class" != "development" && "$run_class" != "candidate" && "$run_class" != "formal" ]]; then
  printf '%s\n' 'NEWSCLAW_EVAL_RUN_CLASS must be development, candidate, or formal.' >&2
  exit 2
fi
prompt_version="${NEWSCLAW_EVAL_PROMPT_VERSION:-}"
if [[ -n "$prompt_version" && "$prompt_version" != "live-agent-evidence-v1" \
      && "$prompt_version" != "live-agent-evidence-v2" \
      && "$prompt_version" != "live-agent-evidence-v3" \
      && "$prompt_version" != "live-agent-evidence-v4-development" \
      && "$prompt_version" != "live-agent-evidence-v4-holdout" \
      && "$prompt_version" != "live-agent-evidence-v5-development" \
      && "$prompt_version" != "live-agent-evidence-v6-development" \
      && "$prompt_version" != "live-agent-evidence-v7-development" \
      && "$prompt_version" != "live-agent-evidence-v8-development" \
      && "$prompt_version" != "live-agent-evidence-v9-relations-development" \
      && "$prompt_version" != "live-agent-evidence-v10-relations-development" ]]; then
  printf '%s\n' 'NEWSCLAW_EVAL_PROMPT_VERSION must be live-agent-evidence-v1/v2/v3/v4-development/v4-holdout/v5-development/v6-development/v7-development/v8-development/v9-relations-development/v10-relations-development or unset.' >&2
  exit 2
fi
case_order="${NEWSCLAW_EVAL_CASE_ORDER:-dataset}"
if [[ "$case_order" != "dataset" && "$case_order" != "reverse" \
      && ! "$case_order" =~ ^rotate-[0-9]+$ ]]; then
  printf '%s\n' 'NEWSCLAW_EVAL_CASE_ORDER must be dataset, reverse, or rotate-N.' >&2
  exit 2
fi

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
declared_prompt_version="$(jq -r '.executionMetadata.promptVersion // "live-agent-evidence-v1"' "$benchmark")"

evaluation_source_fingerprint="$({
  git ls-files -co --exclude-standard -z -- README.md README_en.md pom.xml newsclaw-server scripts docs \
    | LC_ALL=C sort -z \
    | while IFS= read -r -d '' source_file; do
        [[ -f "$source_file" ]] || continue
        source_hash="$(sha256sum -- "$source_file" | awk '{print $1}')"
        printf '%s  %s\n' "$source_hash" "$source_file"
      done
} | sha256sum | awk '{print $1}')"

server_revision="${NEWSCLAW_EVAL_SERVER_REVISION:-}"
if [[ -z "$server_revision" && "$base_url" =~ ^https?://(127\.0\.0\.1|localhost)(:|/) ]] \
    && command -v docker >/dev/null 2>&1; then
  server_revision="$(docker compose images -q newsclaw-server 2>/dev/null | head -n 1 || true)"
fi
primary_review_signoff="${NEWSCLAW_EVAL_PRIMARY_REVIEW_SIGNOFF:-}"
independent_review_signoff="${NEWSCLAW_EVAL_INDEPENDENT_REVIEW_SIGNOFF:-}"

if [[ "$run_class" == "formal" ]]; then
  formal_failures=()
  [[ "$evaluation_tree" == "clean" ]] || formal_failures+=("evaluation tree is dirty")
  [[ "${NEWSCLAW_EVAL_MAX_CASES:-0}" == "0" ]] \
    || formal_failures+=("NEWSCLAW_EVAL_MAX_CASES must be 0")
  [[ -z "$prompt_version" || "$prompt_version" == "$declared_prompt_version" ]] \
    || formal_failures+=("Prompt override differs from dataset metadata")
  [[ -n "$server_revision" ]] || formal_failures+=("NEWSCLAW_EVAL_SERVER_REVISION is required")
  label_review_status="$(jq -r '.executionMetadata.labelReviewStatus // ""' "$benchmark")"
  [[ "$label_review_status" == "two-independent-reviewers-complete" ]] \
    || formal_failures+=("dataset labelReviewStatus must be two-independent-reviewers-complete")
  predeclared_orders=",$(jq -r '.executionMetadata.predeclaredCaseOrders // "dataset"' "$benchmark" | tr -d ' '),"
  [[ "$predeclared_orders" == *",$case_order,"* ]] \
    || formal_failures+=("NEWSCLAW_EVAL_CASE_ORDER must be predeclared by the dataset")
  [[ -n "$primary_review_signoff" ]] \
    || formal_failures+=("NEWSCLAW_EVAL_PRIMARY_REVIEW_SIGNOFF is required")
  [[ -n "$independent_review_signoff" ]] \
    || formal_failures+=("NEWSCLAW_EVAL_INDEPENDENT_REVIEW_SIGNOFF is required")
  [[ -z "$primary_review_signoff" || -z "$independent_review_signoff" \
      || "$primary_review_signoff" != "$independent_review_signoff" ]] \
    || formal_failures+=("primary and independent review signoffs must be distinct")
  if [[ ${#formal_failures[@]} -gt 0 ]]; then
    printf -v formal_message '%s; ' "${formal_failures[@]}"
    printf 'Formal evaluation rejected: %s\n' "${formal_message%; }" >&2
    exit 2
  fi
fi

mvn -q -pl newsclaw-server -am \
  -Dtest=AiNewsLiveAgentBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dgit.commit="$commit" \
  -Dai.news.live.benchmark="$benchmark" \
  -Dai.news.live.base-url="$base_url" \
  -Dai.news.live.agent-id="$NEWSCLAW_EVAL_AGENT_ID" \
  -Dai.news.live.workspace-id="${NEWSCLAW_EVAL_WORKSPACE_ID:-1}" \
  -Dai.news.live.timeout-seconds="${NEWSCLAW_EVAL_TIMEOUT_SECONDS:-240}" \
  -Dai.news.live.max-cases="${NEWSCLAW_EVAL_MAX_CASES:-0}" \
  -Dai.news.live.response-format="$response_format" \
  -Dai.news.live.prompt-version="$prompt_version" \
  -Dai.news.live.thinking-level="$thinking_level" \
  -Dai.news.live.run-class="$run_class" \
  -Dai.news.live.evaluation-source-fingerprint="$evaluation_source_fingerprint" \
  -Dai.news.live.server-revision="$server_revision" \
  -Dai.news.live.case-order="$case_order" \
  -Dai.news.live.primary-review-signoff="$primary_review_signoff" \
  -Dai.news.live.independent-review-signoff="$independent_review_signoff" \
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
    --argjson quality "$(jq -c '{scope:.evaluationScope,dataset:(.datasetId + "@" + .datasetVersion),cases:.caseCounts.total,badcases:(.badcases|length),invalidOutputs:.caseCounts.invalidOutputs,taskSuccess:.metrics.taskSuccess.value,taskSuccessWilson95:[.metrics.taskSuccess.confidenceLower,.metrics.taskSuccess.confidenceUpper],verificationF1:.metrics.verificationEligible.f1,refusalF1:.metrics.properRefusal.f1,citationBlockF1:.metrics.citationViolationBlocked.f1,toolSelectionPassRate:.metrics.toolSelectionCorrect.value,toolParametersPassRate:.metrics.toolParametersCorrect.value,reviewF1:.metrics.humanReviewRouting.f1,routes:.executionMetadata.observedModelRoutes,runClass:.executionMetadata.runClass,caseOrder:.executionMetadata.caseOrder,formalProtocolSatisfied:.executionMetadata.formalProtocolSatisfied,evaluationTree:.executionMetadata.evaluationTree,benchmarkSha256:.executionMetadata.benchmarkSha256,promptContractSha256:.executionMetadata.promptContractSha256,evaluationSourceFingerprint:.executionMetadata.evaluationSourceFingerprint,serverRevision:.executionMetadata.serverRevision}' "$quality_manifest")" \
    --argjson runtime "$(jq -c '{http200:.metrics.http200Rate.value,completed:.metrics.streamCompletedRate.value,structured:.metrics.structuredResponseValidRate.value,jsonContractRequested:.metrics.jsonObjectContractRequestedRate.value,responseFormatAcknowledged:.metrics.responseFormatAcknowledgedRate.value,responseSchemaAcknowledged:.metrics.responseSchemaAcknowledgedRate.value,toolChoiceAcknowledged:.metrics.toolChoiceAcknowledgedRate.value,toolCandidatesAcknowledged:.metrics.toolCandidatesAcknowledgedRate.value,structuredOutputReconciled:.metrics.structuredOutputReconciledRate.value,serverContractEvent:.metrics.serverContractEventRate.value,serverContractValid:.metrics.serverContractValidRate.value,contractParserAgreement:.metrics.serverContractParserAgreementRate.value,e2eP50:.metrics.endToEndLatencyMs.p50,e2eP95:.metrics.endToEndLatencyMs.p95,ttfcP50:.metrics.timeToFirstContentMs.p50,ttfcP95:.metrics.timeToFirstContentMs.p95,promptTokens:.metrics.promptTokens.total,cacheReadTokens:.metrics.cacheReadTokens.total,uncachedPromptTokens:.metrics.uncachedPromptTokens.total,cacheReadTokenShare:.metrics.cacheReadTokenShare.value,cacheHitRequestRate:.metrics.cacheHitRequestRate.value,completionTokens:.metrics.completionTokens.total}' "$runtime_manifest")" \
    '{quality:$quality,runtime:$runtime}'
fi

#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  printf 'Usage: %s --allow-mutation [benchmark.json] [output-root]\n' "${0##*/}" >&2
}

# The stability wrapper delegates to the live runner, whose SSE requests are
# persistent writes.  Keep the same explicit safety gate here so a wrapper
# invocation cannot create output or start a run accidentally.
allow_mutation=false
if [[ "${1:-}" == "--allow-mutation" ]]; then
  allow_mutation=true
  shift
fi
if [[ "$allow_mutation" != true ]]; then
  printf '%s\n' 'Refusing to run: stability evaluation writes conversations/messages through the live runner.' >&2
  printf '%s\n' 'Use --allow-mutation only with NEWSCLAW_EVAL_ISOLATED=1 and a disposable loopback server.' >&2
  exit 2
fi
if [[ "${NEWSCLAW_EVAL_ISOLATED:-}" != "1" ]]; then
  printf '%s\n' 'Refusing mutation: set NEWSCLAW_EVAL_ISOLATED=1 after confirming the target database is disposable.' >&2
  exit 2
fi

if [[ $# -gt 2 ]]; then
  usage
  exit 2
fi

benchmark="${1:-$repo_root/newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-relations-development-v2.json}"
if [[ ! -f "$benchmark" ]]; then
  printf 'Live benchmark not found: %s\n' "$benchmark" >&2
  exit 2
fi
benchmark="$(cd "$(dirname "$benchmark")" && pwd)/$(basename "$benchmark")"

repeats="${NEWSCLAW_EVAL_REPEATS:-3}"
if [[ ! "$repeats" =~ ^[0-9]+$ ]] || (( repeats < 3 || repeats > 5 )); then
  printf '%s\n' 'NEWSCLAW_EVAL_REPEATS must be an integer between 3 and 5.' >&2
  exit 2
fi
if [[ "${NEWSCLAW_EVAL_MAX_CASES:-0}" != "0" ]]; then
  printf '%s\n' 'Stability evaluation requires NEWSCLAW_EVAL_MAX_CASES=0.' >&2
  exit 2
fi
if [[ "${NEWSCLAW_EVAL_RUN_CLASS:-development}" == "formal" ]]; then
  printf '%s\n' 'Repeated observations reuse a viewed dataset and cannot use runClass=formal.' >&2
  exit 2
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
output_root="${2:-$repo_root/target/ai-news-live-agent-stability/$timestamp}"
mkdir -p "$output_root"
output_root="$(cd "$output_root" && pwd)"

# These orderings are deterministic and declared by the current development
# datasets. The first three cover original, reversed, and position-shifted
# contexts; rounds four/five add another shift and a same-order replication.
orders=(dataset reverse rotate-10 rotate-20 dataset)
run_directories=()
export NEWSCLAW_EVAL_RUN_CLASS=development

for (( index=0; index<repeats; index+=1 )); do
  order="${orders[$index]}"
  run_directory="$output_root/repeat-$((index + 1))-$order"
  NEWSCLAW_EVAL_CASE_ORDER="$order" \
    "$repo_root/scripts/run-ai-news-live-agent-eval.sh" --allow-mutation "$benchmark" "$run_directory"
  run_directories+=("$run_directory")
done

analysis_json="$output_root/repeat-analysis.json"
analysis_markdown="$output_root/repeat-analysis.md"
node "$repo_root/scripts/analyze-ai-news-live-runs.js" \
  --output-json "$analysis_json" \
  --output-markdown "$analysis_markdown" \
  "${run_directories[@]}"

printf 'AI_NEWS_STABILITY_ANALYSIS_JSON=%s\n' "$analysis_json"
printf 'AI_NEWS_STABILITY_ANALYSIS_MARKDOWN=%s\n' "$analysis_markdown"
printf '%s\n' 'These repeats are development stability observations, not new independent samples or unseen holdout results.'

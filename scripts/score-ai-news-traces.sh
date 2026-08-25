#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  printf 'Usage: %s <labeled-traces.json> [output-directory]\n' "${0##*/}" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

input="$1"
if [[ ! -f "$input" ]]; then
  printf 'Labeled trace dataset not found: %s\n' "$input" >&2
  exit 2
fi
input="$(cd "$(dirname "$input")" && pwd)/$(basename "$input")"

output_dir="${2:-$repo_root/target/ai-news-trace-evaluation}"
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
dataset_name="$(basename "$input")"
dataset_name="${dataset_name%.json}"
manifest="$output_dir/$dataset_name.manifest.json"
markdown="$output_dir/$dataset_name.report.md"
commit="$(git rev-parse --short HEAD 2>/dev/null || printf 'unknown')"
evaluation_tree="clean"
if [[ -n "$(git status --porcelain --untracked-files=all -- README.md README_en.md pom.xml newsclaw-server scripts docs 2>/dev/null)" ]]; then
  evaluation_tree="dirty"
fi

mvn -q -pl newsclaw-server -am \
  -Dtest=AiNewsTraceQualityEvaluationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dgit.commit="$commit" \
  -Dai.news.quality.evaluation-tree="$evaluation_tree" \
  -Dai.news.quality.input="$input" \
  -Dai.news.quality.manifest="$manifest" \
  -Dai.news.quality.markdown="$markdown" \
  test

printf 'AI_NEWS_TRACE_QUALITY_MANIFEST_PATH=%s\n' "$manifest"
printf 'AI_NEWS_TRACE_QUALITY_MARKDOWN_PATH=%s\n' "$markdown"
if [[ -f "$manifest" ]]; then
  printf 'AI_NEWS_TRACE_QUALITY_SUMMARY='
  jq -c '{schemaVersion,evaluationScope,datasetId,datasetVersion,gitCommit,caseCounts,badcaseCount:(.badcases|length),metrics:(.metrics | with_entries(.value |= {evaluated,value,precision,recall,f1}))}' "$manifest" 2>/dev/null \
    || sed -n '1,120p' "$manifest"
fi

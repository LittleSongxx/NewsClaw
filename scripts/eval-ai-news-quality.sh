#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

manifest="${AI_NEWS_QUALITY_MANIFEST:-$repo_root/target/ai-news-policy-quality-manifest.json}"
markdown="${AI_NEWS_QUALITY_MARKDOWN:-$repo_root/target/ai-news-policy-quality-report.md}"
mkdir -p "$(dirname "$manifest")" "$(dirname "$markdown")"
manifest="$(cd "$(dirname "$manifest")" && pwd)/$(basename "$manifest")"
markdown="$(cd "$(dirname "$markdown")" && pwd)/$(basename "$markdown")"
commit="$(git rev-parse --short HEAD 2>/dev/null || printf 'unknown')"
evaluation_tree="clean"
if [[ -n "$(git status --porcelain --untracked-files=all -- README.md README_en.md pom.xml newsclaw-server scripts docs 2>/dev/null)" ]]; then
  evaluation_tree="dirty"
fi

mvn -q -pl newsclaw-server -am \
  -Dtest=AiNewsPolicyQualityBenchmarkTest,AiNewsQualityEvaluatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dgit.commit="$commit" \
  -Dai.news.quality.evaluation-tree="$evaluation_tree" \
  -Dai.news.quality.manifest="$manifest" \
  -Dai.news.quality.markdown="$markdown" \
  test

printf 'AI_NEWS_QUALITY_MANIFEST_PATH=%s\n' "$manifest"
printf 'AI_NEWS_QUALITY_MARKDOWN_PATH=%s\n' "$markdown"
if [[ -f "$manifest" ]]; then
  printf 'AI_NEWS_QUALITY_SUMMARY='
  jq -c '{schemaVersion,evaluationScope,datasetId,datasetVersion,gitCommit,caseCounts,badcaseCount:(.badcases|length),metrics:(.metrics | with_entries(.value |= {evaluated,invalidPredictions,value,confidenceLower,confidenceUpper,precision,recall,f1,warnings}))}' "$manifest" 2>/dev/null \
    || sed -n '1,120p' "$manifest"
fi

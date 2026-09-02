#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

output_dir="${AI_NEWS_CLUSTERING_OUTPUT_DIR:-$repo_root/target/ai-news-event-clustering}"
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
manifest="${AI_NEWS_CLUSTERING_MANIFEST:-$output_dir/event-clustering-manifest.json}"
markdown="${AI_NEWS_CLUSTERING_MARKDOWN:-$output_dir/event-clustering-report.md}"
commit="$(git rev-parse --short HEAD 2>/dev/null || printf 'unknown')"

mvn -q -pl newsclaw-server -am \
  -Dtest=AiNewsEventClusterScorerTest,AiNewsEventClusteringReplayEvaluationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dgit.commit="$commit" \
  -Dai.news.clustering.manifest="$manifest" \
  -Dai.news.clustering.markdown="$markdown" \
  test

printf 'AI_NEWS_CLUSTERING_MANIFEST_PATH=%s\n' "$manifest"
printf 'AI_NEWS_CLUSTERING_MARKDOWN_PATH=%s\n' "$markdown"
if [[ -f "$manifest" ]]; then
  printf 'AI_NEWS_CLUSTERING_SUMMARY='
  jq -c '{schemaVersion,evaluationScope,datasetId,datasetVersion,gitCommit,algorithmName,algorithmVersion,featureVersion,configHash,passed,counts,headline:{bcubedPrecision:.metrics["clustering.bcubedPrecision"].value,bcubedRecall:.metrics["clustering.bcubedRecall"].value,pairwisePrecision:.metrics["clustering.pairwisePrecision"].value,pairwiseRecall:.metrics["clustering.pairwiseRecall"].value,autoLinkPrecision:.metrics["decision.autoLinkPrecision"].value,assistedDuplicateRecall:.metrics["decision.assistedDuplicateRecall"].value,reviewProposalPrecision:.metrics["decision.reviewProposalPrecision"].value,orderStability:.metrics["stability.coClusterPairJaccard"].value},badcaseCount:(.badcases|length),limitations}' "$manifest" 2>/dev/null \
    || sed -n '1,180p' "$manifest"
fi

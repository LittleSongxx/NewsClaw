#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

input="${1:-${AI_NEWS_DISCOVERY_DATASET:-}}"
output_dir="${2:-${AI_NEWS_DISCOVERY_OUTPUT_DIR:-$repo_root/target/ai-news-discovery-quality}}"
if [[ -z "$input" ]]; then
  printf 'Usage: %s <adjudicated-discovery-dataset.json> [output-directory]\n' "$0" >&2
  exit 2
fi
if [[ ! -f "$input" ]]; then
  printf 'Discovery evaluation dataset does not exist: %s\n' "$input" >&2
  exit 2
fi

input="$(cd "$(dirname "$input")" && pwd)/$(basename "$input")"
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
manifest="${AI_NEWS_DISCOVERY_MANIFEST:-$output_dir/discovery-quality-manifest.json}"
markdown="${AI_NEWS_DISCOVERY_MARKDOWN:-$output_dir/discovery-quality-report.md}"
mkdir -p "$(dirname "$manifest")" "$(dirname "$markdown")"
manifest="$(cd "$(dirname "$manifest")" && pwd)/$(basename "$manifest")"
markdown="$(cd "$(dirname "$markdown")" && pwd)/$(basename "$markdown")"

commit="$(git rev-parse --short HEAD 2>/dev/null || printf 'unknown')"
evaluation_tree="clean"
if [[ -n "$(git status --porcelain --untracked-files=all -- README.md README_en.md pom.xml newsclaw-server scripts docs .github 2>/dev/null)" ]]; then
  evaluation_tree="dirty"
fi

mvn -q -pl newsclaw-server -am \
  -Dtest=AiNewsDiscoveryQualityEvaluationTest,AiNewsDiscoveryQualityEvaluatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dgit.commit="$commit" \
  -Dai.news.discovery.evaluation-tree="$evaluation_tree" \
  -Dai.news.discovery.input="$input" \
  -Dai.news.discovery.manifest="$manifest" \
  -Dai.news.discovery.markdown="$markdown" \
  test

printf 'AI_NEWS_DISCOVERY_MANIFEST_PATH=%s\n' "$manifest"
printf 'AI_NEWS_DISCOVERY_MARKDOWN_PATH=%s\n' "$markdown"
if [[ -f "$manifest" ]]; then
  printf 'AI_NEWS_DISCOVERY_SUMMARY='
  jq -c '
    . as $manifest
    | (($manifest.config.freshnessCutoffsMinutes // [30, 120, 1440]
        | map(select(type == "number" and . > 0))
        | if length == 0 then null else max end)) as $maxFreshnessCutoff
    | (if $maxFreshnessCutoff == null then null
       else $manifest.metrics[("freshness.recallAt" + ($maxFreshnessCutoff | tostring) + "Minutes")].value
       end) as $recallAtMaxCutoff
    | (if $maxFreshnessCutoff == null then null
       else $manifest.metrics[("freshness.evidenceReadyRecallAt" + ($maxFreshnessCutoff | tostring) + "Minutes")].value
       end) as $evidenceReadyRecallAtMaxCutoff
    | {
        schemaVersion,evaluationScope,datasetId,datasetVersion,gitCommit,p0Complete,evaluationEligible,counts,
        headline:{
          eventRecall:$manifest.metrics["retrieval.eventRecall"].value,
          evidenceReadyEventRecall:$manifest.metrics["retrieval.evidenceReadyEventRecall"].value,
          novelEventPrecision:$manifest.metrics["retrieval.novelEventPrecision"].value,
          evidenceReadyNovelPrecision:$manifest.metrics["retrieval.evidenceReadyNovelPrecision"].value,
          freshnessCutoffMinutes:$maxFreshnessCutoff,
          recallAtConfiguredMaxCutoff:$recallAtMaxCutoff,
          evidenceReadyRecallAtConfiguredMaxCutoff:$evidenceReadyRecallAtMaxCutoff,
          recallAt24h:(if $maxFreshnessCutoff == 1440 then $recallAtMaxCutoff else null end),
          evidenceReadyRecallAt24h:(if $maxFreshnessCutoff == 1440 then $evidenceReadyRecallAtMaxCutoff else null end),
          bcubedF1:$manifest.metrics["clustering.bcubedF1"].value,
          claimCitationRecall:$manifest.metrics["evidence.claimCitationRecall"].value
        },
        rankingNdcg:($manifest.metrics
          | with_entries(select(.key | startswith("ranking.ndcgAt")))
          | with_entries(.value = .value.value)),
        warnings:$manifest.warnings
      }' "$manifest" 2>/dev/null \
    || sed -n '1,160p' "$manifest"
fi

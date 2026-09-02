#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

dataset="$repo_root/newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-sealed-holdout-v2.json"
expected_dataset_sha="76b2d9df35506cfc17f639d417b1395039c5e1528b7581d867a65a0d7e0eb00c"

actual_dataset_sha="$(sha256sum "$dataset" | awk '{print $1}')"
if [[ "$actual_dataset_sha" != "$expected_dataset_sha" ]]; then
  echo "sealed holdout hash mismatch: expected=$expected_dataset_sha actual=$actual_dataset_sha" >&2
  exit 1
fi

report_component_status() {
  local metadata_key="$1"
  local component_path="$2"
  local recorded actual
  recorded="$(jq -r --arg key "$metadata_key" '.executionMetadata[$key] | split("sha256:")[1]' "$dataset")"
  if [[ ! "$recorded" =~ ^[0-9a-f]{64}$ ]]; then
    echo "invalid frozen component hash for $metadata_key: $recorded" >&2
    exit 1
  fi
  actual="$(sha256sum "$component_path" | awk '{print $1}')"
  if [[ "$recorded" == "$actual" ]]; then
    printf 'sealed-v2 component %s remains byte-identical (%s)\n' "$metadata_key" "$recorded"
  else
    printf 'sealed-v2 component %s has evolved after first-look (frozen=%s current=%s)\n' \
      "$metadata_key" "$recorded" "$actual"
  fi
}

report_component_status promptFreeze \
  "$repo_root/newsclaw-server/src/test/java/vip/newsclaw/news/evaluation/AiNewsLiveAgentBenchmarkRunner.java"
report_component_status qualityEvaluatorFreeze \
  "$repo_root/newsclaw-server/src/main/java/vip/newsclaw/news/evaluation/AiNewsQualityEvaluator.java"
report_component_status sourceRegistryFreeze \
  "$repo_root/newsclaw-server/src/main/resources/skills/ai_news_radar/references/source_registry.yml"
report_component_status radarSkillFreeze \
  "$repo_root/newsclaw-server/src/main/resources/skills/ai_news_radar/SKILL.md"
report_component_status generatorFreeze \
  "$repo_root/scripts/generate-ai-news-sealed-holdout-v2.js"
report_component_status priorDevelopmentDataset \
  "$repo_root/newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-holdout-100.json"

node --check "$repo_root/scripts/generate-ai-news-sealed-holdout-v2.js"

mvn -q -pl newsclaw-server -am \
  -Dtest=AiNewsSealedHoldoutV2DatasetTest,AiNewsLiveAgentBenchmarkRunnerTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

jq -c '{
  datasetId,
  datasetVersion,
  sha256:"'"$actual_dataset_sha"'",
  cases:(.cases|length),
  semanticGroups:(.cases|map(.slices.semanticGroup)|unique|length),
  archetypes:(.cases|map(.slices.archetype)|unique|length),
  zh:(.cases|map(select(.slices.language=="zh"))|length),
  en:(.cases|map(select(.slices.language=="en"))|length),
  official:(.cases|map(select(.gold.sourceTier=="official"))|length),
  media:(.cases|map(select(.gold.sourceTier=="media"))|length),
  community:(.cases|map(select(.gold.sourceTier=="community"))|length),
  verificationTrue:(.cases|map(select(.gold.verificationEligible))|length),
  citationTrue:(.cases|map(select(.gold.citationAllowed))|length),
  quoteTrue:(.cases|map(select(.gold.claimQuoteSupported))|length),
  conflicts:(.cases|map(select(.gold.unresolvedConflict))|length),
  highRisk:(.cases|map(select(.slices.risk=="high"))|length),
  requiredTools:(.cases|map(select(.toolExpectation.mode=="required"))|length)
}' "$dataset"

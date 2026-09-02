#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

mvn -q -pl newsclaw-server -am \
  -Dtest=AiNewsHoldoutBenchmarkDatasetTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

dataset="$repo_root/newsclaw-server/src/test/resources/evals/ai-news/live-agent-evidence-holdout-100.json"
jq -c '
  def semantic_group: sub("^holdout-[0-9]+-"; "") | sub("-(zh|en)$"; "");
  {
    datasetId,
    datasetVersion,
    cases:(.cases|length),
    semanticGroups:(.cases|map(.id|semantic_group)|unique|length),
    pairedSemanticGroups:(.cases|map(.id|semantic_group)|group_by(.)|map(select(length==2))|length),
    zh:(.cases|map(select(.slices.language=="zh"))|length),
    en:(.cases|map(select(.slices.language=="en"))|length),
    requiredTools:(.cases|map(select(.toolExpectation.mode=="required"))|length)
  }' "$dataset"

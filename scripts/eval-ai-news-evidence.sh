#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

manifest="${AI_NEWS_EVIDENCE_MANIFEST:-$repo_root/target/ai-news-evidence-manifest.json}"
commit="$(git rev-parse --short HEAD 2>/dev/null || printf 'unknown')"

mvn -q -pl newsclaw-server -am \
  -Dtest=AiNewsEvidenceManifestEvaluationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dgit.commit="$commit" \
  -Dai.news.eval.manifest="$manifest" \
  test

printf 'AI_NEWS_EVIDENCE_MANIFEST_PATH=%s\n' "$manifest"
if [[ -f "$manifest" ]]; then
  printf 'AI_NEWS_EVIDENCE_MANIFEST_SUMMARY='
  jq -c '{schemaVersion,evaluationScope,caseCounts,metrics,badcaseCount:(.badcases|length)}' "$manifest" 2>/dev/null \
    || sed -n '1,80p' "$manifest"
fi

#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  printf 'Usage: %s [new-output-directory]\n' "${0##*/}" >&2
}

if [[ "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi
if [[ $# -gt 1 ]]; then
  usage
  exit 2
fi
for command in git jq mktemp mvn node sha256sum; do
  command -v "$command" >/dev/null || { printf 'Missing command: %s\n' "$command" >&2; exit 2; }
done

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
output="${1:-$repo_root/target/ai-news-p0-demo/$timestamp}"
if [[ -e "$output" ]]; then
  printf 'Refusing to overwrite output: %s\n' "$output" >&2
  exit 2
fi
mkdir -p "$output"
output="$(cd "$output" && pwd)"

printf '[1/5] Freeze deterministic candidate input\n'
node scripts/generate-ai-news-p0-demo-fixture.js "$output/fixture" \
  >"$output/fixture.log" 2>&1
node scripts/prepare-ai-news-candidate-shadow-review.js \
  "$output/fixture" "$output/review-package" \
  >"$output/review-package.log" 2>&1
node scripts/test-prepare-ai-news-candidate-shadow-review.js \
  >"$output/review-package-self-check.log" 2>&1
node scripts/test-finalize-ai-news-candidate-shadow-review.js \
  >>"$output/review-package-self-check.log" 2>&1

printf '[2/5] Measure deterministic evidence policy\n'
AI_NEWS_QUALITY_MANIFEST="$output/quality-manifest.json" \
AI_NEWS_QUALITY_MARKDOWN="$output/quality-report.md" \
  ./scripts/eval-ai-news-quality.sh >"$output/quality-eval.log" 2>&1

printf '[3/5] Run candidate/failure/security contract regressions\n'
tests='AiNewsCandidatePipelineIntegrationTest,AiNewsCandidatePipelineServiceTest,AiNewsCandidatePromotionServiceTest,AiNewsCandidateCaptureWorkerTest,AiNewsAtomicFactGuardTest,AiNewsSourceCaptureServiceTest,AiNewsIngestionLedgerIntegrationTest,AiNewsDiscoverySearchServiceTest,AiNewsScanOrchestratorTest,AiNewsCandidateToolTest'
backend_start_marker="$output/backend-test-started-at"
node -p 'Date.now()' >"$backend_start_marker"
backend_raw_log="$(mktemp /tmp/newsclaw-p0-mvn.XXXXXX.log)"
if ! mvn -q -pl newsclaw-server -am \
  -Dtest="$tests" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dlogging.level.root=ERROR \
  test >"$backend_raw_log" 2>&1; then
  cp "$backend_raw_log" "$output/backend-test.log"
  printf 'P0_BACKEND_SUITE_FAIL\n' >>"$output/backend-test.log"
  exit 1
fi
{
  printf 'Focused Maven suite completed with exit code 0.\n'
  printf 'Surefire XML reports are authoritative for test counts.\n'
  printf 'P0_BACKEND_SUITE_PASS\n'
} >"$output/backend-test.log"

printf '[4/5] Build Bad Case loop and evidence manifest\n'
node scripts/build-ai-news-p0-report.js \
  "$output" "$output/fixture" "$output/review-package" \
  "$output/quality-manifest.json" "$output/backend-test.log" "$backend_start_marker"

printf '[5/5] Verify the bundle\n'
node --check scripts/generate-ai-news-p0-demo-fixture.js
node --check scripts/build-ai-news-p0-report.js
bash -n scripts/run-ai-news-p0-demo.sh
git diff --check
jq -e '
  .status == "PASS_WITH_BOUNDARIES"
  and ([.stages[].status] | all(. == "PASS"))
  and .candidate.blindLeakageAuditPassed == true
  and .quality.badcases == 0
' "$output/p0-manifest.json" >/dev/null

printf 'AI_NEWS_P0_OUTPUT=%s\n' "$output"
printf 'AI_NEWS_P0_SUMMARY='
jq -c '{status,gitCommit,evaluationTree,quality,candidate,stages:(.stages|map({id,status}))}' \
  "$output/p0-manifest.json"
printf '%s\n' 'Boundary: offline synthetic/contract evidence only; no open-web quality or publish-success claim.'

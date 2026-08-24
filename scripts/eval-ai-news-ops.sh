#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# This is a deterministic regression/evaluation suite. It never reads .env,
# calls a model provider, sends an IM message, or publishes external content.
mvn -pl newsclaw-server -am \
  -Dtest=AiNewsOpsPolicyEvaluationTest,AiNewsEventServiceTest,OfficialSourceEvidenceCaptureServiceTest,JdkOfficialSourceHttpFetcherTest,MemoryWriteGovernanceServiceTest,SkillChangeProposalServiceTest,SkillReflectionServiceTest,SkillRoutinePromoterProposalTest,ExternalEffectServiceTest,CronJobIdempotencyTest,AbstractCronResultDeliveryTest,LlmRoutingTraceServiceTest,NodeStreamingChatHelperRoutingTraceTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

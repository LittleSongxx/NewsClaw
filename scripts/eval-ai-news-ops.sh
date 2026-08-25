#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# This is a deterministic regression/evaluation suite. It never reads .env,
# calls a model provider, sends an IM message, or publishes external content.
mvn -pl newsclaw-server -am \
  -Dtest=AiNewsOpsPolicyEvaluationTest,AiNewsEventServiceTest,OfficialSourceEvidenceCaptureServiceTest,JdkOfficialSourceHttpFetcherTest,AiNewsModelRouterTest,NewsSourceProviderRegistryTest,AiNewsEventToolSourceTest,AiNewsWorkflowTemplateServiceTest,AiNewsFeedbackServiceTest,MemoryWriteGovernanceServiceTest,SkillChangeProposalServiceTest,SkillReflectionServiceTest,SkillRoutinePromoterProposalTest,ToolExecutionExecutorLoadSkillTest,ActionNodeLoadSkillTest,ReasoningNodeLongFormPolicyTest,StateGraphReActAgentStreamedContentDeltaTest,ExternalEffectServiceTest,CronJobIdempotencyTest,AbstractCronResultDeliveryTest,LlmRoutingTraceServiceTest,NodeStreamingChatHelperRoutingTraceTest,AiNewsPolicyQualityBenchmarkTest,AiNewsQualityEvaluatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

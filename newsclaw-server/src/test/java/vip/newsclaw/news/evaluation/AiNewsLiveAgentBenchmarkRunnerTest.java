package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsLiveAgentBenchmarkRunnerTest {

    @Test
    void parsesDeduplicatedToolCallsAndRuntimeMetadataFromSse() throws Exception {
        String sse = """
                event:stream_started
                data:{\"responseFormat\":\"json_object\",\"responseSchema\":\"ai_news_decision_v1\",\"toolChoice\":\"function:ai_news_event\",\"toolCandidates\":[\"ai_news_event\"]}

                event:tool_call_started
                data:{\"toolCallId\":\"call-1\",\"toolName\":\"ai_news_event\",\"arguments\":\"{\\\"action\\\":\\\"source_health\\\"}\"}

                event:tool_call_completed
                data:{\"toolCallId\":\"call-1\",\"toolName\":\"ai_news_event\",\"success\":true,\"result\":\"{\\\"ok\\\":true}\"}

                event:tool_call_started
                data:{\"toolCallId\":\"call-1\",\"toolName\":\"ai_news_event\",\"arguments\":\"{\\\"action\\\":\\\"source_health\\\"}\"}

                event:content_delta
                data:{\"delta\":\"{\\\"sourceTier\\\":\\\"official\\\",\"}

                event:content_delta
                data:{\"delta\":\"\\\"verificationEligible\\\":true,\\\"citationAllowed\\\":true,\\\"claimQuoteSupported\\\":true,\\\"refusalIssued\\\":false,\\\"humanReviewRequested\\\":false,\\\"citationIds\\\":[\\\"E1\\\"]}\"}

                event:perf_summary
                data:{\"phase\":\"tool_execution\",\"tool_exec_ms\":\"7\"}

                event:structured_output
                data:{\"requestedFormat\":\"json_object\",\"enforcement\":\"provider_and_server\",\"status\":\"valid\",\"valid\":true,\"terminalAnswerReached\":true,\"failureReason\":\"\"}

                event:structured_output_reconciled
                data:{\"reason\":\"single_json_fence_validated\"}

                event:done
                data:{\"status\":\"completed\",\"runtimeProvider\":\"bailian-team\",\"runtimeModel\":\"qwen3.7-plus\",\"promptTokens\":100,\"completionTokens\":20,\"reasoningTokens\":3,\"cacheReadTokens\":40,\"cacheWriteTokens\":5}

                """;

        AiNewsLiveAgentBenchmarkRunner.SseCapture capture = AiNewsLiveAgentBenchmarkRunner.readSse(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), System.nanoTime());

        assertEquals("completed", capture.streamStatus());
        assertEquals("bailian-team", capture.runtimeProvider());
        assertEquals("qwen3.7-plus", capture.runtimeModel());
        assertEquals(100L, capture.promptTokens());
        assertEquals(40L, capture.cacheReadTokens());
        assertEquals(5L, capture.cacheWriteTokens());
        assertEquals(7L, capture.toolExecutionMs());
        assertEquals("json_object", capture.observedResponseFormat());
        assertEquals("ai_news_decision_v1", capture.observedResponseSchema());
        assertEquals("function:ai_news_event", capture.observedToolChoice());
        assertEquals(List.of("ai_news_event"), capture.observedToolCandidates());
        assertEquals("single_json_fence_validated", capture.structuredOutputReconciliationReason());
        assertTrue(capture.structuredOutputContract().present());
        assertTrue(capture.structuredOutputContract().valid());
        assertEquals("valid", capture.structuredOutputContract().status());
        assertEquals(1, capture.toolCalls().size(), "duplicate SSE projections share the same toolCallId");
        assertTrue(capture.toolCalls().getFirst().successful());
        assertEquals("ai_news_event", capture.toolCalls().getFirst().toolName());
        assertEquals("official", AiNewsLiveAgentBenchmarkRunner.parseDecision(capture.assistantContent()).sourceTier(),
                "answer should remain parseable after SSE chunks");
        assertTrue(AiNewsLiveAgentBenchmarkRunner.parseDecision(capture.assistantContent()).valid());
    }

    @Test
    void rejectsMarkdownAndMissingStructuredFields() {
        AiNewsLiveAgentBenchmarkRunner.OutputDecision fenced = AiNewsLiveAgentBenchmarkRunner.parseDecision("""
                ```json
                {"sourceTier":"official","verificationEligible":true,"citationAllowed":true,"claimQuoteSupported":true,"refusalIssued":false,"humanReviewRequested":false,"citationIds":["E1"]}
                ```
                """);
        assertFalse(fenced.valid());
        assertEquals("official", fenced.sourceTier());
        assertTrue(fenced.verificationEligible());

        AiNewsLiveAgentBenchmarkRunner.OutputDecision incomplete = AiNewsLiveAgentBenchmarkRunner.parseDecision(
                "{\"sourceTier\":\"official\",\"verificationEligible\":true}");
        assertFalse(incomplete.valid());
        assertTrue(incomplete.verificationEligible());
        assertNull(incomplete.citationAllowed(),
                "missing booleans must remain missing instead of becoming false predictions");

        String valid = """
                {"sourceTier":"official","verificationEligible":true,"citationAllowed":true,"claimQuoteSupported":true,"refusalIssued":false,"humanReviewRequested":false,"citationIds":["E1"]}
                """;
        assertFalse(AiNewsLiveAgentBenchmarkRunner.parseDecision(
                valid.replace("\"official\"", "\"Official\"")).valid());
        assertFalse(AiNewsLiveAgentBenchmarkRunner.parseDecision(
                valid.replace("\"official\"", "\" official \"")).valid());
        assertFalse(AiNewsLiveAgentBenchmarkRunner.parseDecision(
                valid.replace("\"citationIds\"", "\"extra\":1,\"citationIds\"")).valid());
        assertFalse(AiNewsLiveAgentBenchmarkRunner.parseDecision(
                valid.replace("\"sourceTier\":\"official\"",
                        "\"sourceTier\":\"community\",\"sourceTier\":\"official\"")).valid());
        assertFalse(AiNewsLiveAgentBenchmarkRunner.parseDecision(
                valid.replace("\"E1\"", "\" E1 \"")).valid());
    }

    @Test
    void citationListMustExactlyMatchTheRequestedId() {
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, false, false, true, true, null, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "citation-contract", Map.of(), "synthetic", List.of("E1", "E2"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold);

        assertTrue(AiNewsLiveAgentBenchmarkRunner.citationsMatch(item,
                decisionWithCitations(List.of("E1"))));
        assertFalse(AiNewsLiveAgentBenchmarkRunner.citationsMatch(item,
                decisionWithCitations(List.of("E1", "E2"))),
                "an additional allowed id still violates the exact-list contract");
        assertFalse(AiNewsLiveAgentBenchmarkRunner.citationsMatch(item,
                decisionWithCitations(List.of("E1", "E1"))),
                "duplicate ids must not be collapsed into a set");
        assertFalse(AiNewsLiveAgentBenchmarkRunner.citationsMatch(item,
                decisionWithCitations(List.of("E2"))));
    }

    @Test
    void normalizesOmittedZeroUsageDetailsAndDerivesUncachedInput() throws Exception {
        String sse = """
                event:done
                data:{"status":"completed","promptTokens":100,"completionTokens":20}

                """;

        AiNewsLiveAgentBenchmarkRunner.SseCapture capture = AiNewsLiveAgentBenchmarkRunner.readSse(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), System.nanoTime());

        assertEquals(0L, capture.cacheReadTokens());
        assertEquals(0L, capture.cacheWriteTokens());
        assertEquals(0L, capture.reasoningTokens());
        assertEquals(100L, AiNewsLiveAgentBenchmarkRunner.uncachedPromptTokens(
                capture.promptTokens(), capture.cacheReadTokens()));
        assertNull(AiNewsLiveAgentBenchmarkRunner.uncachedPromptTokens(100L, null));
        AiNewsLiveAgentBenchmarkRunner.RuntimeMetric ratio = AiNewsLiveAgentBenchmarkRunner.RuntimeMetric
                .ratio(2, 25, 100, "correlated token ratio");
        assertEquals(0.25D, ratio.value());
        assertEquals(2, ratio.evaluated());
        assertEquals(100L, ratio.total());
    }

    @Test
    void validatesFrozenDatasetAndRejectsBrokenCaseInvariants() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = new ObjectMapper().readValue(
                getClass().getClassLoader().getResourceAsStream(
                        "evals/ai-news/live-agent-evidence-v3.json"),
                AiNewsLiveAgentBenchmarkRunner.LiveBenchmark.class);
        AiNewsLiveAgentBenchmarkRunner.validateBenchmark(benchmark);

        AiNewsQualityEvaluator.GoldLabel validGold = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, false, false, true, true, null, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase duplicateCitationIds =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "broken", Map.of(), "synthetic", List.of("E1", "E1"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), validGold);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.validateBenchmarkCase(duplicateCitationIds));
        assertTrue(error.getMessage().contains("citation request metadata"));

        AiNewsQualityEvaluator.GoldLabel inconsistentRefusal = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, true, false, true, true, null, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase brokenRefusal =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "broken-refusal", Map.of(), "synthetic", List.of("E1"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), inconsistentRefusal);
        assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.validateBenchmarkCase(brokenRefusal));

        AiNewsQualityEvaluator.GoldLabel unsupportedVerification = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, false, false, null, false, false, true, true, null, true);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase brokenSupport =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "broken-support", Map.of(), "synthetic", List.of("E1"), "OUTSIDE",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), unsupportedVerification);
        assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.validateBenchmarkCase(brokenSupport));

        AiNewsQualityEvaluator.GoldLabel failedRequiredParameters = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, false, false, true, true, false, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase brokenParameters =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "broken-parameters", Map.of(), "synthetic", List.of("E1"), "E1",
                        new AiNewsLiveAgentBenchmarkRunner.ToolExpectation(
                                "required", "ai_news_event", Map.of("action", "source_health")),
                        failedRequiredParameters);
        assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.validateBenchmarkCase(brokenParameters));

        AiNewsQualityEvaluator.GoldLabel missingConflictLabel = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, false, null, true, true, null, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase brokenConflictCoverage =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "broken-conflict-coverage", Map.of(), "synthetic", List.of("E1"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), missingConflictLabel);
        assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.validateBenchmarkCase(brokenConflictCoverage));
    }

    @Test
    void evaluatesRequiredReadOnlyToolArgumentsExactly() throws Exception {
        String sse = """
                event:stream_started
                data:{\"responseFormat\":\"json_object\",\"responseSchema\":\"ai_news_decision_v1\",\"toolChoice\":\"function:ai_news_event\"}

                event:tool_call_started
                data:{\"toolCallId\":\"call-1\",\"toolName\":\"ai_news_event\",\"arguments\":\"{\\\"action\\\":\\\"source_health\\\"}\"}

                event:tool_call_completed
                data:{\"toolCallId\":\"call-1\",\"toolName\":\"ai_news_event\",\"success\":true,\"result\":\"{\\\"status\\\":\\\"ok\\\"}\"}

                event:content_delta
                data:{\"delta\":\"{\\\"sourceTier\\\":\\\"official\\\",\\\"verificationEligible\\\":true,\\\"citationAllowed\\\":true,\\\"claimQuoteSupported\\\":true,\\\"refusalIssued\\\":false,\\\"humanReviewRequested\\\":false,\\\"citationIds\\\":[\\\"E1\\\"]}\"}

                event:structured_output
                data:{\"requestedFormat\":\"json_object\",\"enforcement\":\"provider_and_server\",\"status\":\"valid\",\"valid\":true,\"terminalAnswerReached\":true,\"failureReason\":\"\"}

                event:done
                data:{\"status\":\"completed\"}

                """;
        AiNewsLiveAgentBenchmarkRunner.SseCapture capture = AiNewsLiveAgentBenchmarkRunner.readSse(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), System.nanoTime());
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase caseSpec = new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                "tool-probe", Map.of("route", "tool"), "synthetic", List.of("E1"), "E1",
                new AiNewsLiveAgentBenchmarkRunner.ToolExpectation("required", "ai_news_event",
                        Map.of("action", "source_health")),
                new AiNewsQualityEvaluator.GoldLabel("official", true, true, true, null,
                        false, false, true, true, true, false));

        AiNewsLiveAgentBenchmarkRunner.CaseRun run = capture.toCaseRun(
                "tool-probe", "conv", 200, 12, "json_object", "function:ai_news_event");
        AiNewsQualityEvaluator.QualityCase scored = AiNewsLiveAgentBenchmarkRunner.toQualityCase(caseSpec, run);

        assertTrue(run.jsonContractRequested());
        assertEquals("json_object", run.observedResponseFormat());
        assertEquals("ai_news_decision_v1", run.observedResponseSchema());
        assertTrue(run.responseSchemaAcknowledged());
        assertTrue(run.toolChoiceAcknowledged());
        assertTrue(run.serverContractSatisfied());
        assertEquals(AiNewsLiveAgentBenchmarkRunner.parseDecision(run.assistantContent()).valid(),
                run.structuredOutputContract().valid());
        assertTrue(scored.prediction().toolSelectionCorrect());
        assertTrue(scored.prediction().toolParametersCorrect());
        assertTrue(scored.prediction().taskSucceeded());

        String withoutAcknowledgement = sse.replace(
                "event:stream_started\ndata:{\"responseFormat\":\"json_object\",\"responseSchema\":\"ai_news_decision_v1\",\"toolChoice\":\"function:ai_news_event\"}\n\n", "");
        AiNewsLiveAgentBenchmarkRunner.CaseRun unacknowledged = AiNewsLiveAgentBenchmarkRunner.readSse(
                        new ByteArrayInputStream(withoutAcknowledgement.getBytes(StandardCharsets.UTF_8)),
                        System.nanoTime())
                .toCaseRun("tool-probe", "conv-unacknowledged", 200, 12, "json_object",
                        "function:ai_news_event");
        assertFalse(unacknowledged.responseFormatAcknowledged());
        assertFalse(unacknowledged.responseSchemaAcknowledged());
        assertFalse(unacknowledged.toolChoiceAcknowledged());
        assertFalse(AiNewsLiveAgentBenchmarkRunner.toQualityCase(caseSpec, unacknowledged)
                .prediction().taskSucceeded());

        String withoutSchema = sse.replace(
                ",\"responseSchema\":\"ai_news_decision_v1\"", "");
        AiNewsLiveAgentBenchmarkRunner.CaseRun schemaUnacknowledged = AiNewsLiveAgentBenchmarkRunner.readSse(
                        new ByteArrayInputStream(withoutSchema.getBytes(StandardCharsets.UTF_8)),
                        System.nanoTime())
                .toCaseRun("tool-probe", "conv-schema-unacknowledged", 200, 12, "json_object",
                        "function:ai_news_event");
        assertFalse(schemaUnacknowledged.responseSchemaAcknowledged());
        assertFalse(AiNewsLiveAgentBenchmarkRunner.toQualityCase(caseSpec, schemaUnacknowledged)
                .prediction().taskSucceeded());

        String withoutTerminalAnswer = sse.replace(
                "\"terminalAnswerReached\":true", "\"terminalAnswerReached\":false");
        AiNewsLiveAgentBenchmarkRunner.CaseRun nonTerminal = AiNewsLiveAgentBenchmarkRunner.readSse(
                        new ByteArrayInputStream(withoutTerminalAnswer.getBytes(StandardCharsets.UTF_8)),
                        System.nanoTime())
                .toCaseRun("tool-probe", "conv-nonterminal", 200, 12, "json_object");
        assertFalse(nonTerminal.serverContractSatisfied());
        assertFalse(AiNewsLiveAgentBenchmarkRunner.toQualityCase(caseSpec, nonTerminal)
                .prediction().taskSucceeded());
    }

    @Test
    void v3ToolChoicePolicyIsReportedAsEnforcedOrchestration() {
        AiNewsLiveAgentBenchmarkRunner.ToolExpectation required =
                new AiNewsLiveAgentBenchmarkRunner.ToolExpectation("required", "ai_news_event",
                        Map.of("action", "source_health"));
        AiNewsLiveAgentBenchmarkRunner.ToolExpectation forbidden =
                new AiNewsLiveAgentBenchmarkRunner.ToolExpectation("forbidden", null, Map.of());
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, false, false, true, true, true, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase requiredCase =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase("required", Map.of(), "synthetic",
                        List.of("E1"), "E1", required, gold);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase forbiddenCase =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase("forbidden", Map.of(), "synthetic",
                        List.of("E1"), "E1", forbidden, gold);

        assertEquals("function:ai_news_event", AiNewsLiveAgentBenchmarkRunner.requestedToolChoice(
                requiredCase, "exact-function-for-required-and-none-for-forbidden"));
        assertEquals("none", AiNewsLiveAgentBenchmarkRunner.requestedToolChoice(
                forbiddenCase, "exact-function-for-required-and-none-for-forbidden"));
        assertEquals("auto", AiNewsLiveAgentBenchmarkRunner.requestedToolChoice(requiredCase, "auto"));
        assertTrue(AiNewsLiveAgentBenchmarkRunner.samplingDescription(2, 30)
                .contains("not a representative quality estimate"));
        assertTrue(AiNewsLiveAgentBenchmarkRunner.samplingDescription(30, 30)
                .startsWith("all 30 frozen cases"));
    }

    @Test
    void autonomousToolProtocolKeepsExpectedToolAndArgumentsOutOfTheInstruction() {
        AiNewsLiveAgentBenchmarkRunner.ToolExpectation autonomous =
                new AiNewsLiveAgentBenchmarkRunner.ToolExpectation(
                        "required", "ai_news_event", Map.of("action", "source_health"), "autonomous");
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, false, false, true, true, true, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "autonomous", Map.of(), "Inspect provider availability, then assess this synthetic packet.",
                        List.of("E1"), "E1", autonomous, gold);

        String prompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(
                item, "live-agent-evidence-v3");

        assertEquals("auto", AiNewsLiveAgentBenchmarkRunner.requestedToolChoice(
                item, "exact-function-for-required-and-none-for-forbidden"));
        assertTrue(prompt.contains("Decide autonomously"));
        assertFalse(prompt.contains("`ai_news_event`"));
        assertFalse(prompt.contains("source_health"));
    }

    @Test
    void autonomousToolProtocolRequiresTheServerToAcknowledgeAuto() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.ToolExpectation noTool =
                new AiNewsLiveAgentBenchmarkRunner.ToolExpectation(
                        "forbidden", "", Map.of(), "autonomous");
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, false, false, true, true, null, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "auto-ack", Map.of(), "synthetic", List.of("E1"), "E1", noTool, gold);
        String body = "{\"sourceTier\":\"official\",\"verificationEligible\":true,"
                + "\"citationAllowed\":true,\"claimQuoteSupported\":true,\"refusalIssued\":false,"
                + "\"humanReviewRequested\":false,\"citationIds\":[\"E1\"]}";
        String withoutAck = """
                event:stream_started
                data:{"responseFormat":"json_object","responseSchema":"ai_news_decision_v1"}

                event:content_delta
                data:{"delta":%s}

                event:structured_output
                data:{"requestedFormat":"json_object","enforcement":"provider_and_server","status":"valid","valid":true,"terminalAnswerReached":true,"failureReason":""}

                event:done
                data:{"status":"completed"}

                """.formatted(new ObjectMapper().writeValueAsString(body));
        AiNewsLiveAgentBenchmarkRunner.CaseRun missing = AiNewsLiveAgentBenchmarkRunner.readSse(
                        new ByteArrayInputStream(withoutAck.getBytes(StandardCharsets.UTF_8)),
                        System.nanoTime())
                .toCaseRun(item.id(), "missing", 200, 10, "json_object", "auto");
        String acknowledgedSse = withoutAck.replace(
                "\"responseSchema\":\"ai_news_decision_v1\"",
                "\"responseSchema\":\"ai_news_decision_v1\",\"toolChoice\":\"auto\"");
        AiNewsLiveAgentBenchmarkRunner.CaseRun acknowledged = AiNewsLiveAgentBenchmarkRunner.readSse(
                        new ByteArrayInputStream(acknowledgedSse.getBytes(StandardCharsets.UTF_8)),
                        System.nanoTime())
                .toCaseRun(item.id(), "ack", 200, 10, "json_object", "auto");

        assertFalse(AiNewsLiveAgentBenchmarkRunner.toQualityCase(item, missing)
                .prediction().taskSucceeded());
        assertTrue(AiNewsLiveAgentBenchmarkRunner.toQualityCase(item, acknowledged)
                .prediction().taskSucceeded());
    }

    @Test
    void deterministicCaseOrdersSupportRepeatabilityRuns() {
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, false, false, true, true, null, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase first =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "first", Map.of(), "first", List.of("E1"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase second =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "second", Map.of(), "second", List.of("E1"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold);

        assertEquals(List.of("second", "first"),
                AiNewsLiveAgentBenchmarkRunner.orderedCases(List.of(first, second), "reverse")
                        .stream().map(AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase::id).toList());
        assertEquals(List.of("second", "first"),
                AiNewsLiveAgentBenchmarkRunner.orderedCases(List.of(first, second), "rotate-1")
                        .stream().map(AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase::id).toList());
        assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.normalizeCaseOrder("random"));
    }

    @Test
    void v4DevelopmentPromptKeepsEvidenceDimensionsIndependent() {
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, false, true, null, false, false, true, true, null, true);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "v4-prompt", Map.of(), "synthetic case", List.of("E1"), "OUTSIDE",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold);

        String prompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(item, "live-agent-evidence-v4-development");

        assertTrue(prompt.contains("Make these decisions independently"));
        assertTrue(prompt.contains("Never change A, B, or D merely because"));
        assertFalse(prompt.contains("live-27"), "the candidate prompt must not embed known case answers");
        assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.renderPrompt(item, "candidate-containing-v4-typo"));
        assertEquals("live-agent-evidence-v3",
                AiNewsLiveAgentBenchmarkRunner.normalizePromptVersion("LIVE-AGENT-EVIDENCE-V3"));
    }

    @Test
    void v5DevelopmentPromptKeepsConflictAndQuoteSupportOrthogonal() {
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "media", false, false, true, null, true, true, true, true, null, true);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "v5-prompt", Map.of(), "synthetic conflict", List.of("E1", "E2"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold);

        String prompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(item,
                "live-agent-evidence-v5-development");

        assertTrue(prompt.contains("protocol v5"));
        assertTrue(prompt.contains("D=false, C=true, or E=false MUST NOT change B"));
        assertTrue(prompt.contains("supporting quote plus unresolved conflict     true    true    false"));
        assertTrue(prompt.contains("For safety, security,"));
        assertTrue(prompt.contains("compliance, or other explicitly high-risk"));
        assertTrue(prompt.contains("huggingface.co"));
        assertTrue(prompt.contains("`/synthetic/`"));
        assertTrue(prompt.contains("refusalIssued=(NOT D)"));
        assertFalse(prompt.contains("holdout-085"), "development prompt must not embed holdout answers");
        assertEquals("live-agent-evidence-v5-development",
                AiNewsLiveAgentBenchmarkRunner.normalizePromptVersion("LIVE-AGENT-EVIDENCE-V5-DEVELOPMENT"));
    }

    @Test
    void v6DevelopmentPromptAddsFinalHostAndRiskAudit() {
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "community", false, false, false, null, false, true, true, true, null, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "v6-prompt", Map.of(), "synthetic case", List.of("E1"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold);

        String prompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(item,
                "live-agent-evidence-v6-development");

        assertTrue(prompt.contains("FINAL MECHANICAL AUDIT"));
        assertTrue(prompt.contains("openai.com.synthetic.invalid"));
        assertTrue(prompt.contains("media-only high-risk"));
        assertEquals("live-agent-evidence-v6-development",
                AiNewsLiveAgentBenchmarkRunner.normalizePromptVersion("LIVE-AGENT-EVIDENCE-V6-DEVELOPMENT"));
    }

    @Test
    void v7DevelopmentPromptIncludesCompleteRegistrySnapshot() {
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "media", false, false, true, null, true, false, true, true, null, true);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "v7-prompt", Map.of(), "synthetic case", List.of("E1"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold);

        String prompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(item,
                "live-agent-evidence-v7-development");

        assertTrue(prompt.contains("AUTHORITATIVE source_registry.yml snapshot"));
        assertTrue(prompt.contains("ai.meta.com"));
        assertTrue(prompt.contains("reuters.com"));
        assertTrue(prompt.contains("https://github.com/QwenLM/"));
        assertTrue(prompt.contains("theverge.com"));
        assertTrue(prompt.contains("Publisher text never upgrades"));
        assertEquals("live-agent-evidence-v7-development",
                AiNewsLiveAgentBenchmarkRunner.normalizePromptVersion("LIVE-AGENT-EVIDENCE-V7-DEVELOPMENT"));
    }

    @Test
    void v8DevelopmentUsesTheFrozenExpandedRegistrySnapshot() {
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "media", false, false, true, null, true, false, true, true, null, true);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "v8-prompt", Map.of(), "synthetic case", List.of("E1"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold);

        String prompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(item,
                "live-agent-evidence-v8-development");

        assertTrue(prompt.contains("bloomberg.com"));
        assertTrue(prompt.contains("wired.com"));
        assertTrue(prompt.contains("wsj.com"));
        assertEquals("live-agent-evidence-v8-development",
                AiNewsLiveAgentBenchmarkRunner.normalizePromptVersion("LIVE-AGENT-EVIDENCE-V8-DEVELOPMENT"));
    }

    @Test
    void v9AsksOnlyForRelationsAndBackendAggregatesPolicy() {
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, false, false,
                true, true, null, false);
        AiNewsLiveAgentBenchmarkRunner.PolicyPacket packet =
                new AiNewsLiveAgentBenchmarkRunner.PolicyPacket(false, false, List.of(
                        new AiNewsLiveAgentBenchmarkRunner.PolicyEvidence(
                                "D1", "https://openai.com/dev/d1", "Nova is available to everyone.", "entails"),
                        new AiNewsLiveAgentBenchmarkRunner.PolicyEvidence(
                                "D2", "https://forum.invalid/dev/d2", "Nova remains invite-only.", "contradicts")));
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "v9-relations", Map.of(), "Primary claim: Nova is available to everyone.",
                        List.of("D1", "D2"), "D1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold, packet);

        String prompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(
                item, "live-agent-evidence-v9-relations-development");
        AiNewsLiveAgentBenchmarkRunner.OutputDecision output =
                AiNewsLiveAgentBenchmarkRunner.parseOutput(item, """
                        {"relations":[
                          {"evidenceId":"D1","relation":"entails","confidence":0.98},
                          {"evidenceId":"D2","relation":"contradicts","confidence":0.91}
                        ]}
                        """);

        assertTrue(prompt.contains("model is allowed to judge semantics only")
                || prompt.contains("Judge each quote independently"));
        assertTrue(prompt.contains("A different artifact, audience, document, feature or time is not a conflict"));
        assertFalse(prompt.contains("AUTHORITATIVE source_registry.yml snapshot"));
        assertTrue(output.valid());
        assertTrue(output.verificationEligible(),
                "a community contradiction cannot block backend-trusted official support");
        assertTrue(output.citationAllowed());
        assertEquals("live-agent-evidence-v9-relations-development",
                AiNewsLiveAgentBenchmarkRunner.normalizePromptVersion(
                        "LIVE-AGENT-EVIDENCE-V9-RELATIONS-DEVELOPMENT"));
    }

    @Test
    void v10IsolatesSemanticInputAndPinsEveryEvidenceIdAtTheOutputBoundary() {
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, false, true, null, false, false,
                true, true, null, true);
        AiNewsLiveAgentBenchmarkRunner.PolicyPacket packet =
                new AiNewsLiveAgentBenchmarkRunner.PolicyPacket(
                        "Nova is available to every registered developer.", false, false, List.of(
                        new AiNewsLiveAgentBenchmarkRunner.PolicyEvidence(
                                "D1", "https://openai.com/dev/d1",
                                "Nova is available to every registered developer.", "entails"),
                        new AiNewsLiveAgentBenchmarkRunner.PolicyEvidence(
                                "D2", "https://forum.invalid/dev/d2",
                                "A 2024 preview of Nova was invite-only.", "unrelated")));
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "v10-relations", Map.of(),
                        "Primary claim: Nova is available to every registered developer.\n"
                                + "Evidence Packet includes URL https://openai.com/dev/d1 and publisher OpenAI.\n"
                                + "Requested citation ID: OUTSIDE-ID.",
                        List.of("D1", "D2"), "OUTSIDE-ID",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold, packet);

        String prompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(
                item, "live-agent-evidence-v10-relations-development");

        assertTrue(prompt.contains("Nova is available to every registered developer."));
        assertTrue(prompt.contains("A 2024 preview of Nova was invite-only."));
        assertTrue(prompt.contains("{\"evidenceId\":\"D1\",\"quote\":\"Nova is available to every registered developer.\"}"),
                "the model-view field order is part of the byte-stable prompt contract");
        assertTrue(prompt.contains("Required evidenceId sequence: [\"D1\",\"D2\"]"));
        assertTrue(prompt.contains("exactly 2 items"));
        assertFalse(prompt.contains("OUTSIDE-ID"));
        assertFalse(prompt.contains("https://openai.com"));
        assertFalse(prompt.contains("publisher OpenAI"));
        assertFalse(prompt.contains("expectedRelation"));
        assertEquals("live-agent-evidence-v10-relations-development",
                AiNewsLiveAgentBenchmarkRunner.normalizePromptVersion(
                        "LIVE-AGENT-EVIDENCE-V10-RELATIONS-DEVELOPMENT"));
    }

    @Test
    void holdoutPromptIsASeparateFrozenProtocolWithoutKnownCaseAnswers() {
        AiNewsQualityEvaluator.GoldLabel gold = new AiNewsQualityEvaluator.GoldLabel(
                "official", true, true, true, null, false, false, true, true, null, false);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase(
                        "holdout-prompt", Map.of(), "synthetic case", List.of("E1"), "E1",
                        AiNewsLiveAgentBenchmarkRunner.ToolExpectation.forbidden(), gold);

        String prompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(item, "live-agent-evidence-v4-holdout");

        assertTrue(prompt.contains("holdout evaluation"));
        assertTrue(prompt.contains("Before emitting the answer, decide independently"));
        assertTrue(prompt.contains("citationAllowed=(D AND E)"));
        assertFalse(prompt.contains("live-01"), "holdout prompt must not embed old case answers");
        assertEquals("live-agent-evidence-v4-holdout",
                AiNewsLiveAgentBenchmarkRunner.normalizePromptVersion("LIVE-AGENT-EVIDENCE-V4-HOLDOUT"));
    }

    @Test
    void formalRunProtocolRejectsDirtyPartialOrUnidentifiedExecutions() {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmark(
                        "dataset", "v1", "controlled", Map.of(
                                "labelReviewStatus", "two-independent-reviewers-complete",
                                "predeclaredCaseOrders", "dataset,reverse"), List.of(), List.of());
        AiNewsLiveAgentBenchmarkRunner.LiveConfig dirty = liveConfig(
                "formal", "dirty", "source-fingerprint", "server-image", "reviewer@freeze");
        IllegalArgumentException dirtyError = assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.validateRunProtocol(dirty, benchmark, 0, false));
        assertTrue(dirtyError.getMessage().contains("evaluationTree must be clean"));

        AiNewsLiveAgentBenchmarkRunner.LiveConfig unidentified = liveConfig(
                "formal", "clean", "", "", "");
        IllegalArgumentException identityError = assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.validateRunProtocol(
                        unidentified, benchmark, 0, true));
        assertTrue(identityError.getMessage().contains("Prompt must not be overridden"));
        assertTrue(identityError.getMessage().contains("serverRevision is required"));
        assertTrue(identityError.getMessage().contains("primaryReviewSignoff is required"));
        assertTrue(identityError.getMessage().contains("independentReviewSignoff is required"));

        AiNewsLiveAgentBenchmarkRunner.LiveConfig formal = liveConfig(
                "formal", "clean", "source-fingerprint", "server-image", "reviewer@freeze");
        assertDoesNotThrow(() -> AiNewsLiveAgentBenchmarkRunner.validateRunProtocol(
                formal, benchmark, 0, false));

        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark pendingReview =
                new AiNewsLiveAgentBenchmarkRunner.LiveBenchmark(
                        "dataset", "v1", "controlled",
                        Map.of("labelReviewStatus", "pending-two-independent-reviewers"),
                        List.of(), List.of());
        IllegalArgumentException pendingError = assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.validateRunProtocol(
                        formal, pendingReview, 0, false));
        assertTrue(pendingError.getMessage().contains("two-independent-reviewers-complete"));

        AiNewsLiveAgentBenchmarkRunner.LiveConfig duplicateReviewers = new AiNewsLiveAgentBenchmarkRunner.LiveConfig(
                "http://127.0.0.1:18080", "user", "password", 1L, 1L,
                Duration.ofSeconds(10), 0, "commit", "clean", null,
                "json_object", "", "", "formal", "source-fingerprint",
                "server-image", "dataset", "same@freeze", "same@freeze");
        IllegalArgumentException duplicateError = assertThrows(IllegalArgumentException.class,
                () -> AiNewsLiveAgentBenchmarkRunner.validateRunProtocol(
                        duplicateReviewers, benchmark, 0, false));
        assertTrue(duplicateError.getMessage().contains("must be distinct"));

        AiNewsLiveAgentBenchmarkRunner.LiveConfig development = liveConfig(
                "development", "dirty", "", "", "");
        assertDoesNotThrow(() -> AiNewsLiveAgentBenchmarkRunner.validateRunProtocol(
                development, benchmark, 0, true));
    }

    @Test
    void candidateScopedProtocolRequiresExactStreamAcknowledgement() throws Exception {
        String sse = """
                event:stream_started
                data:{\"responseFormat\":\"json_object\",\"responseSchema\":\"ai_news_decision_v1\",\"toolChoice\":\"auto\",\"toolCandidates\":[\"ai_news_event\"]}

                event:done
                data:{\"status\":\"completed\"}

                """;
        AiNewsLiveAgentBenchmarkRunner.SseCapture capture = AiNewsLiveAgentBenchmarkRunner.readSse(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), System.nanoTime());

        AiNewsLiveAgentBenchmarkRunner.CaseRun acknowledged = capture.toCaseRun(
                "candidate", "conv", 200, 10, "json_object", "auto", List.of("ai_news_event"));
        AiNewsLiveAgentBenchmarkRunner.CaseRun wrong = capture.toCaseRun(
                "candidate", "conv", 200, 10, "json_object", "auto", List.of("other"));

        assertTrue(acknowledged.toolCandidatesAcknowledged());
        assertFalse(wrong.toolCandidatesAcknowledged());
    }

    private static AiNewsLiveAgentBenchmarkRunner.LiveConfig liveConfig(
            String runClass, String tree, String sourceFingerprint,
            String serverRevision, String signoff) {
        return new AiNewsLiveAgentBenchmarkRunner.LiveConfig(
                "http://127.0.0.1:18080", "user", "password", 1L, 1L,
                Duration.ofSeconds(10), 0, "commit", tree, null,
                "json_object", "", "", runClass, sourceFingerprint,
                serverRevision, "dataset", signoff.isBlank() ? "" : "primary@freeze", signoff);
    }

    private static AiNewsLiveAgentBenchmarkRunner.OutputDecision decisionWithCitations(List<String> citations) {
        return new AiNewsLiveAgentBenchmarkRunner.OutputDecision(
                "official", true, true, true, false, false,
                citations, Map.of(), true, List.of());
    }
}

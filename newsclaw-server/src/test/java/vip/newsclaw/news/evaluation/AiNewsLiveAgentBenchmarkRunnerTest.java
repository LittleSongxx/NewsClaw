package vip.newsclaw.news.evaluation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsLiveAgentBenchmarkRunnerTest {

    @Test
    void parsesDeduplicatedToolCallsAndRuntimeMetadataFromSse() throws Exception {
        String sse = """
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

                event:done
                data:{\"status\":\"completed\",\"runtimeProvider\":\"bailian-team\",\"runtimeModel\":\"qwen3.7-plus\",\"promptTokens\":100,\"completionTokens\":20,\"reasoningTokens\":3}

                """;

        AiNewsLiveAgentBenchmarkRunner.SseCapture capture = AiNewsLiveAgentBenchmarkRunner.readSse(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), System.nanoTime());

        assertEquals("completed", capture.streamStatus());
        assertEquals("bailian-team", capture.runtimeProvider());
        assertEquals("qwen3.7-plus", capture.runtimeModel());
        assertEquals(7L, capture.toolExecutionMs());
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
    }

    @Test
    void evaluatesRequiredReadOnlyToolArgumentsExactly() throws Exception {
        String sse = """
                event:tool_call_started
                data:{\"toolCallId\":\"call-1\",\"toolName\":\"ai_news_event\",\"arguments\":\"{\\\"action\\\":\\\"source_health\\\"}\"}

                event:tool_call_completed
                data:{\"toolCallId\":\"call-1\",\"toolName\":\"ai_news_event\",\"success\":true,\"result\":\"{\\\"status\\\":\\\"ok\\\"}\"}

                event:content_delta
                data:{\"delta\":\"{\\\"sourceTier\\\":\\\"official\\\",\\\"verificationEligible\\\":true,\\\"citationAllowed\\\":true,\\\"claimQuoteSupported\\\":true,\\\"refusalIssued\\\":false,\\\"humanReviewRequested\\\":false,\\\"citationIds\\\":[\\\"E1\\\"]}\"}

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

        AiNewsLiveAgentBenchmarkRunner.CaseRun run = capture.toCaseRun("tool-probe", "conv", 200, 12);
        AiNewsQualityEvaluator.QualityCase scored = AiNewsLiveAgentBenchmarkRunner.toQualityCase(caseSpec, run);

        assertTrue(scored.prediction().toolSelectionCorrect());
        assertTrue(scored.prediction().toolParametersCorrect());
        assertTrue(scored.prediction().taskSucceeded());
    }
}

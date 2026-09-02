package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsToolAutonomyDevelopmentDatasetTest {

    private static final String RESOURCE =
            "evals/ai-news/live-agent-tool-autonomy-development-v1.json";
    private static final String ISOLATED_RESOURCE =
            "evals/ai-news/live-agent-tool-autonomy-development-v2.json";
    private static final String INTENT_RESOURCE =
            "evals/ai-news/live-agent-tool-autonomy-development-v3.json";
    private static final String CANDIDATE_SCOPED_RESOURCE =
            "evals/ai-news/live-agent-tool-autonomy-development-v4.json";

    @Test
    void toolChoiceIsAutoBalancedAndDoesNotLeakScorerOnlyAnswers() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = load(RESOURCE);

        AiNewsLiveAgentBenchmarkRunner.validateBenchmark(benchmark);
        assertEquals(30, benchmark.cases().size());
        assertEquals("auto-autonomous-selection",
                benchmark.executionMetadata().get("toolChoicePolicy"));
        assertEquals("pending-two-independent-reviewers",
                benchmark.executionMetadata().get("labelReviewStatus"));
        assertEquals(15, benchmark.cases().stream()
                .filter(item -> "required".equals(item.toolExpectation().mode())).count());
        assertEquals(15, benchmark.cases().stream()
                .filter(item -> item.toolExpectation().forbidsTools()).count());
        assertEquals(8, benchmark.cases().stream()
                .filter(item -> "zh".equals(item.slices().get("language")))
                .filter(item -> !item.toolExpectation().forbidsTools()).count());
        assertEquals(7, benchmark.cases().stream()
                .filter(item -> "en".equals(item.slices().get("language")))
                .filter(item -> !item.toolExpectation().forbidsTools()).count());

        for (AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item : benchmark.cases()) {
            assertTrue(item.toolExpectation().autonomous());
            assertEquals("auto", AiNewsLiveAgentBenchmarkRunner.requestedToolChoice(
                    item, benchmark.executionMetadata().get("toolChoicePolicy")));
            String rendered = AiNewsLiveAgentBenchmarkRunner.renderPrompt(
                    item, benchmark.executionMetadata().get("promptVersion"));
            assertFalse(rendered.contains("`ai_news_event`"), item.id());
            assertFalse(rendered.contains("source_health"), item.id());
        }
        assertTrue(benchmark.limitations().stream()
                .anyMatch(value -> value.contains("never unseen holdout evidence")));
    }

    @Test
    void v2ShowsOperationalIntentButStillHidesTheScorerToolAnswer() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = load(ISOLATED_RESOURCE);

        AiNewsLiveAgentBenchmarkRunner.validateBenchmark(benchmark);
        assertEquals("live-agent-evidence-v10-relations-development",
                benchmark.executionMetadata().get("promptVersion"));
        assertEquals(15, benchmark.cases().stream()
                .filter(item -> "required".equals(item.toolExpectation().mode())).count());
        assertEquals(15, benchmark.cases().stream()
                .filter(item -> item.toolExpectation().forbidsTools()).count());
        assertTrue(benchmark.cases().stream().allMatch(item ->
                !item.policyPacket().primaryClaim().isBlank()
                        && !item.policyPacket().operationalRequest().isBlank()));

        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase required = benchmark.cases().get(0);
        String rendered = AiNewsLiveAgentBenchmarkRunner.renderPrompt(required,
                benchmark.executionMetadata().get("promptVersion"));
        assertTrue(rendered.contains("结构化新闻来源是否健康"));
        assertTrue(rendered.contains("Required evidenceId sequence"));
        assertFalse(rendered.contains("source_health"));
        assertFalse(rendered.contains("`ai_news_event`"));
        assertFalse(rendered.contains("https://openai.com"));
        assertFalse(rendered.contains("请求引用 ID"));
        assertEquals("auto", AiNewsLiveAgentBenchmarkRunner.requestedToolChoice(required,
                benchmark.executionMetadata().get("toolChoicePolicy")));
    }

    @Test
    void v3MakesTheRequestedActionUnambiguousWithoutNamingTheScorerTool() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = load(INTENT_RESOURCE);

        AiNewsLiveAgentBenchmarkRunner.validateBenchmark(benchmark);
        assertEquals("ai-news-live-agent-tool-autonomy-development-v2@2026-08-26-v2",
                benchmark.executionMetadata().get("derivedFrom"));
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase required = benchmark.cases().get(0);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase forbidden = benchmark.cases().get(2);
        String requiredPrompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(required,
                benchmark.executionMetadata().get("promptVersion"));
        String forbiddenPrompt = AiNewsLiveAgentBenchmarkRunner.renderPrompt(forbidden,
                benchmark.executionMetadata().get("promptVersion"));

        assertTrue(requiredPrompt.contains("即使检查结果不影响语义关系，也不得跳过"));
        assertTrue(requiredPrompt.contains("first output byte MUST be `{`"));
        assertTrue(forbiddenPrompt.contains("没有要求部署状态、来源检索或任何外部检查"));
        for (String prompt : new String[]{requiredPrompt, forbiddenPrompt}) {
            assertFalse(prompt.contains("source_health"));
            assertFalse(prompt.contains("`ai_news_event`"));
            assertFalse(prompt.contains("https://openai.com"));
        }
    }

    @Test
    void v4ChangesOnlyExecutionMetadataAndDoesNotLeakRequiredVersusForbiddenLabels() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark v2 = load(ISOLATED_RESOURCE);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark v4 = load(CANDIDATE_SCOPED_RESOURCE);

        AiNewsLiveAgentBenchmarkRunner.validateBenchmark(v4);
        assertEquals(List.of("ai_news_event"),
                AiNewsLiveAgentBenchmarkRunner.requestedToolCandidates(v4.executionMetadata()));
        assertEquals("two-human-decisions-confirmed-ai-assisted-transcription",
                v4.executionMetadata().get("labelReviewStatus"));
        assertEquals(v2.cases(), v4.cases(),
                "candidate-scoped protocol must not modify reviewed cases or scorer labels");
        assertEquals(15, v4.cases().stream()
                .filter(item -> item.toolExpectation().forbidsTools()).count());
        assertEquals(15, v4.cases().stream()
                .filter(item -> "required".equals(item.toolExpectation().mode())).count());
    }

    private AiNewsLiveAgentBenchmarkRunner.LiveBenchmark load(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing test resource " + resource);
            return new ObjectMapper().readValue(input,
                    AiNewsLiveAgentBenchmarkRunner.LiveBenchmark.class);
        }
    }
}

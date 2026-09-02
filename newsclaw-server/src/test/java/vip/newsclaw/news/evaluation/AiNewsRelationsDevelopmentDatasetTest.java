package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsRelationsDevelopmentDatasetTest {

    private static final String RESOURCE =
            "evals/ai-news/live-agent-evidence-relations-development-v1.json";
    private static final String ISOLATED_RESOURCE =
            "evals/ai-news/live-agent-evidence-relations-development-v2.json";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void developmentSetIsDistinctBalancedAndPolicyRecomputable() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = load(RESOURCE,
                AiNewsLiveAgentBenchmarkRunner.LiveBenchmark.class);

        AiNewsLiveAgentBenchmarkRunner.validateBenchmark(benchmark);
        assertEquals(30, benchmark.cases().size());
        assertEquals("live-agent-evidence-v9-relations-development",
                benchmark.executionMetadata().get("promptVersion"));
        assertEquals("development-not-holdout",
                benchmark.executionMetadata().get("datasetClass"));
        assertEquals("dataset,reverse,rotate-10,rotate-20",
                benchmark.executionMetadata().get("predeclaredCaseOrders"));
        assertEquals("pending-two-independent-reviewers",
                benchmark.executionMetadata().get("labelReviewStatus"));
        assertEquals(15, countSlice(benchmark.cases(), "language", "zh"));
        assertEquals(15, countSlice(benchmark.cases(), "language", "en"));
        assertEquals(30, benchmark.cases().stream()
                .map(item -> item.slices().get("semanticGroup")).distinct().count());
        assertTrue(benchmark.cases().stream()
                .map(item -> item.slices().get("archetype")).distinct().count() >= 12);
        assertEquals(6, benchmark.cases().stream()
                .filter(item -> !item.toolExpectation().forbidsTools()).count());
        assertTrue(benchmark.cases().stream().allMatch(item -> item.policyPacket() != null));

        Set<String> relationKinds = new HashSet<>();
        benchmark.cases().stream().flatMap(item -> item.policyPacket().evidence().stream())
                .map(AiNewsLiveAgentBenchmarkRunner.PolicyEvidence::expectedRelation)
                .forEach(relationKinds::add);
        assertEquals(Set.of("entails", "contradicts", "partial", "unrelated", "hedged"),
                relationKinds);
    }

    @Test
    void developmentPromptsDoNotCopyEitherRetiredEvaluationSet() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark development = load(RESOURCE,
                AiNewsLiveAgentBenchmarkRunner.LiveBenchmark.class);
        Set<String> oldPrompts = new HashSet<>();
        oldPrompts.addAll(prompts("evals/ai-news/live-agent-evidence-holdout-100.json"));
        oldPrompts.addAll(prompts("evals/ai-news/live-agent-evidence-sealed-holdout-v2.json"));

        assertFalse(development.cases().stream().map(AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase::prompt)
                .anyMatch(oldPrompts::contains));
        assertTrue(development.limitations().stream()
                .anyMatch(value -> value.contains("development data")));
    }

    @Test
    void v2KeepsTheVisibleCasesButExcludesPolicyInputsFromTheModelPrompt() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = load(ISOLATED_RESOURCE,
                AiNewsLiveAgentBenchmarkRunner.LiveBenchmark.class);

        AiNewsLiveAgentBenchmarkRunner.validateBenchmark(benchmark);
        assertEquals(30, benchmark.cases().size());
        assertEquals("live-agent-evidence-v10-relations-development",
                benchmark.executionMetadata().get("promptVersion"));
        assertEquals("ai-news-live-agent-evidence-relations-development-v1@2026-08-26-v1",
                benchmark.executionMetadata().get("derivedFrom"));
        assertTrue(benchmark.cases().stream().allMatch(item ->
                item.policyPacket() != null && !item.policyPacket().primaryClaim().isBlank()));

        String promptContract = benchmark.cases().stream()
                .map(item -> item.id() + "\n" + AiNewsLiveAgentBenchmarkRunner.renderPrompt(
                        item, benchmark.executionMetadata().get("promptVersion")))
                .reduce("", (left, right) -> left + "\n\u0000\n" + right);
        assertEquals("3b17b637dd32e05f0a597eb314eaf869e6b377f4e5ef6d9b2fdcc4a847cc707c",
                AiNewsLiveAgentBenchmarkRunner.sha256(promptContract),
                "v10 model-view rendering must stay byte-stable across JVM processes");

        AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase outOfPacket = benchmark.cases().get(9);
        String rendered = AiNewsLiveAgentBenchmarkRunner.renderPrompt(outOfPacket,
                benchmark.executionMetadata().get("promptVersion"));
        assertTrue(rendered.contains("Cobalt API supports 256k-token inputs."));
        assertTrue(rendered.contains("Required evidenceId sequence: [\"D1\"]"));
        assertFalse(rendered.contains("OUT-G10"));
        assertFalse(rendered.contains("https://openai.com"));
        assertFalse(rendered.contains("publisher OpenAI"));
    }

    private long countSlice(List<AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase> cases,
                            String slice, String value) {
        return cases.stream().filter(item -> value.equals(item.slices().get(slice))).count();
    }

    private Set<String> prompts(String resource) throws Exception {
        JsonNode root = load(resource, JsonNode.class);
        Set<String> prompts = new HashSet<>();
        root.path("cases").forEach(item -> prompts.add(item.path("prompt").asText()));
        return prompts;
    }

    private <T> T load(String resource, Class<T> type) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing test resource " + resource);
            return json.readValue(input, type);
        }
    }
}

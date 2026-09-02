package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pre-run structural, composition, registry, and immutability gates for sealed holdout v2. */
class AiNewsSealedHoldoutV2DatasetTest {

    private static final String RESOURCE =
            "/evals/ai-news/live-agent-evidence-sealed-holdout-v2.json";
    private static final String PRIOR_DEVELOPMENT_RESOURCE =
            "/evals/ai-news/live-agent-evidence-holdout-100.json";
    private static final String FROZEN_SHA256 =
            "76b2d9df35506cfc17f639d417b1395039c5e1528b7581d867a65a0d7e0eb00c";
    private static final String FROZEN_PROMPT_CONTRACT_SHA256 =
            "1e9850aa16f1203795e9b94704824193e400bdd893a13f4d410a163c1321b9e6";
    private static final Pattern URL = Pattern.compile("https?://[^\\s|`]+", Pattern.CASE_INSENSITIVE);

    @Test
    void hasOneHundredDistinctBalancedScenariosWithPreRegisteredLabels() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = readBenchmark(RESOURCE);

        AiNewsLiveAgentBenchmarkRunner.validateBenchmark(benchmark);

        assertEquals("controlled-live-ai-news-agent-evidence-sealed-holdout-v2", benchmark.datasetId());
        assertEquals("2026-08-26-sealed-v2", benchmark.datasetVersion());
        assertEquals("controlled-live-agent-evidence-policy-json-contract-sealed-holdout-v2",
                benchmark.evaluationScope());
        assertEquals("live-agent-evidence-v8-development",
                benchmark.executionMetadata().get("promptVersion"));
        assertEquals("0x5eed2026", benchmark.executionMetadata().get("orderSeed"));
        assertEquals("AI-authored and mechanically adjudicated in the evaluation session; "
                        + "not independently human-reviewed",
                benchmark.executionMetadata().get("independentReviewStatus"));

        assertEquals(100, benchmark.cases().size());
        assertEquals(100, distinctCount(benchmark, AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase::id));
        assertEquals(100, distinctCount(benchmark, AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase::prompt));
        assertEquals(100, benchmark.cases().stream()
                .map(item -> item.slices().get("semanticGroup")).distinct().count());
        assertEquals(40, benchmark.cases().stream()
                .map(item -> item.slices().get("archetype")).distinct().count());
        assertTrue(benchmark.cases().stream().allMatch(item -> item.id().matches("sealed-v2-\\d{3}")));
        assertTrue(benchmark.cases().stream()
                .allMatch(item -> item.prompt().startsWith("Synthetic scenario, not real news.")));

        assertEquals(50, sliceCount(benchmark, "language", "zh"));
        assertEquals(50, sliceCount(benchmark, "language", "en"));
        assertEquals(21, sliceCount(benchmark, "risk", "high"));
        assertEquals(20, sliceCount(benchmark, "route", "read-only-tool"));
        assertEquals(Map.ofEntries(
                Map.entry("citation-boundary", 15L),
                Map.entry("conflict", 13L),
                Map.entry("conflict-resistance", 2L),
                Map.entry("corroboration", 18L),
                Map.entry("entailment", 15L),
                Map.entry("high-risk", 10L),
                Map.entry("mixed-tier", 4L),
                Map.entry("source-integrity", 23L)),
                sliceDistribution(benchmark, "scenarioFamily"));

        assertEquals(40, goldTierCount(benchmark, "official"));
        assertEquals(35, goldTierCount(benchmark, "media"));
        assertEquals(25, goldTierCount(benchmark, "community"));
        assertEquals(37, benchmark.cases().stream()
                .filter(item -> item.gold().verificationEligible()).count());
        assertEquals(29, benchmark.cases().stream()
                .filter(item -> item.gold().citationAllowed()).count());
        assertEquals(58, benchmark.cases().stream()
                .filter(item -> item.gold().claimQuoteSupported()).count());
        assertEquals(15, benchmark.cases().stream()
                .filter(item -> item.gold().unresolvedConflict()).count());
        assertEquals(10, benchmark.cases().stream().map(item -> List.of(
                        item.gold().sourceTier(), item.gold().verificationEligible(),
                        item.gold().citationAllowed(), item.gold().claimQuoteSupported(),
                        item.gold().refusalRequired(), item.gold().unresolvedConflict(),
                        item.gold().humanReviewRequired()))
                .distinct().count());

        List<AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase> requiredToolCases = benchmark.cases().stream()
                .filter(item -> item.toolExpectation().scoresParameters()).toList();
        assertEquals(20, requiredToolCases.size());
        assertTrue(requiredToolCases.stream().allMatch(item ->
                "ai_news_event".equals(item.toolExpectation().toolName())
                        && Map.of("action", "source_health").equals(item.toolExpectation().arguments())));
        assertFalse(benchmark.cases().stream().anyMatch(item ->
                item.gold().unresolvedConflict() && item.gold().verificationEligible()));
    }

    @Test
    void containsNoExactPromptFromThePriorDevelopmentSet() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark sealed = readBenchmark(RESOURCE);
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark prior = readBenchmark(PRIOR_DEVELOPMENT_RESOURCE);
        Set<String> priorPrompts = prior.cases().stream()
                .map(AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase::prompt)
                .collect(Collectors.toSet());

        assertTrue(sealed.cases().stream().noneMatch(item -> priorPrompts.contains(item.prompt())),
                "a sealed prompt must not be copied from the development benchmark");
    }

    @Test
    void frozenPayloadMatchesThePreRunHash() throws Exception {
        try (InputStream input = AiNewsSealedHoldoutV2DatasetTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(input, "sealed benchmark resource must be packaged");
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            assertEquals(FROZEN_SHA256, actual,
                    "never edit the sealed payload in place; create a new dataset id/version instead");
        }
    }

    @Test
    void legacyV8RenderedPromptContractRemainsFrozenWhileTheRunnerEvolves() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = readBenchmark(RESOURCE);
        String rendered = benchmark.cases().stream()
                .map(item -> item.id() + "\n" + AiNewsLiveAgentBenchmarkRunner.renderPrompt(
                        item, "live-agent-evidence-v8-development"))
                .reduce("", (left, right) -> left + "\n\u0000\n" + right);

        assertEquals(FROZEN_PROMPT_CONTRACT_SHA256,
                AiNewsLiveAgentBenchmarkRunner.sha256(rendered),
                "new protocols must not mutate the already observed v8 Prompt contract");
    }

    @Test
    void goldSourceTierMatchesTheFrozenPreRunRegistrySnapshot() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = readBenchmark(RESOURCE);
        AiNewsSourceRegistry registry = new AiNewsEvaluationSourceRegistry();

        for (AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item : benchmark.cases()) {
            List<String> urls = URL.matcher(item.prompt()).results()
                    .map(match -> stripTrailingPunctuation(match.group())).toList();
            String strongest = urls.stream().anyMatch(registry::isOfficialUrl) ? "official"
                    : urls.stream().anyMatch(registry::isTrustedMediaUrl) ? "media" : "community";
            assertEquals(strongest, item.gold().sourceTier(),
                    () -> "sourceTier disagrees with the frozen registry for " + item.id() + ": " + urls);
        }
    }

    private static long distinctCount(AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark,
                                      Function<AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase, String> field) {
        return benchmark.cases().stream().map(field).distinct().count();
    }

    private static long sliceCount(AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark,
                                   String key, String value) {
        return benchmark.cases().stream().filter(item -> value.equals(item.slices().get(key))).count();
    }

    private static Map<String, Long> sliceDistribution(AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark,
                                                       String key) {
        return benchmark.cases().stream().map(item -> item.slices().get(key))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static long goldTierCount(AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark, String tier) {
        return benchmark.cases().stream().filter(item -> tier.equals(item.gold().sourceTier())).count();
    }

    private static String stripTrailingPunctuation(String value) {
        return value == null ? "" : value.replaceFirst("[),.;。！!？?]+$", "");
    }

    private static AiNewsLiveAgentBenchmarkRunner.LiveBenchmark readBenchmark(String resource) throws Exception {
        try (InputStream input = AiNewsSealedHoldoutV2DatasetTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, "benchmark resource must be packaged: " + resource);
            return new ObjectMapper().readValue(input, AiNewsLiveAgentBenchmarkRunner.LiveBenchmark.class);
        }
    }
}

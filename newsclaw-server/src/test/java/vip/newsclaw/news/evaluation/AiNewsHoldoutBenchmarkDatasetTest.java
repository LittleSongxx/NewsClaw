package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural and protocol gate for the newly authored 100-case candidate holdout. */
class AiNewsHoldoutBenchmarkDatasetTest {

    private static final String RESOURCE =
            "/evals/ai-news/live-agent-evidence-holdout-100.json";
    private static final String FROZEN_SHA256 =
            "145a26bb49c009911a0f3434758eda4d53273036d692765142a2fb56a159051a";
    private static final Pattern URL = Pattern.compile("https?://[^\\s|`]+", Pattern.CASE_INSENSITIVE);

    @Test
    void hasExactlyOneHundredBalancedFrozenCases() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = readBenchmark();

        AiNewsLiveAgentBenchmarkRunner.validateBenchmark(benchmark);

        assertEquals("controlled-live-ai-news-agent-evidence-holdout-100", benchmark.datasetId());
        assertEquals("2026-08-26-holdout-100-v1", benchmark.datasetVersion());
        assertEquals("live-agent-evidence-v4-holdout",
                benchmark.executionMetadata().get("promptVersion"));
        assertEquals(100, benchmark.cases().size());
        assertEquals(50, benchmark.cases().stream()
                .filter(item -> "zh".equals(item.slices().get("language"))).count());
        assertEquals(50, benchmark.cases().stream()
                .filter(item -> "en".equals(item.slices().get("language"))).count());
        assertEquals(20, benchmark.cases().stream()
                .filter(item -> item.toolExpectation().scoresParameters()).count());
        assertEquals(100, benchmark.cases().stream()
                .map(AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase::id)
                .distinct().count());
        assertTrue(benchmark.cases().stream().noneMatch(item -> item.id().startsWith("live-")));
        assertTrue(benchmark.cases().stream()
                .allMatch(item -> item.prompt().startsWith("Synthetic scenario, not real news.")));

        Map<String, Long> semanticGroups = benchmark.cases().stream()
                .collect(Collectors.groupingBy(item -> item.id()
                        .replaceFirst("^holdout-\\d+-", "")
                        .replaceFirst("-(zh|en)$", ""), Collectors.counting()));
        assertEquals(55, semanticGroups.size(),
                "100 authored rows represent 55 semantic scenarios, not 100 independent scenarios");
        assertEquals(45, semanticGroups.values().stream().filter(count -> count == 2L).count());
        assertEquals(10, semanticGroups.values().stream().filter(count -> count == 1L).count());

        Map<String, Long> families = benchmark.cases().stream()
                .map(item -> item.slices().get("scenarioFamily"))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertEquals(Map.ofEntries(
                Map.entry("official-direct", 20L),
                Map.entry("independent-media-pair", 16L),
                Map.entry("citation-boundary", 10L),
                Map.entry("single-media", 10L),
                Map.entry("hedged-media", 4L),
                Map.entry("community", 10L),
                Map.entry("spoofed-domain", 4L),
                Map.entry("quote-mismatch", 10L),
                Map.entry("unresolved-conflict", 10L),
                Map.entry("mixed-source", 4L),
                Map.entry("missing-relevant-evidence", 2L)), families);

        Set<String> expectedSourceTiers = Set.of("official", "media", "community");
        assertTrue(benchmark.cases().stream()
                .allMatch(item -> expectedSourceTiers.contains(item.gold().sourceTier())));
        assertFalse(benchmark.cases().stream()
                .anyMatch(item -> item.gold().unresolvedConflict()
                        && item.gold().verificationEligible()));
    }

    @Test
    void frozenPayloadMatchesThePublishedHash() throws Exception {
        try (InputStream input = AiNewsHoldoutBenchmarkDatasetTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(input, "holdout benchmark resource must be packaged");
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            assertEquals(FROZEN_SHA256, actual,
                    "never edit a scored holdout in place; create a new dataset id/version instead");
        }
    }

    @Test
    void frozenGoldRecordsOnlyTheDeclaredRegistryDrift() throws Exception {
        AiNewsLiveAgentBenchmarkRunner.LiveBenchmark benchmark = readBenchmark();
        vip.newsclaw.news.service.AiNewsSourceRegistry registry =
                new vip.newsclaw.news.service.AiNewsSourceRegistry();

        Set<String> driftedCases = new java.util.LinkedHashSet<>();
        for (AiNewsLiveAgentBenchmarkRunner.LiveBenchmarkCase item : benchmark.cases()) {
            List<String> urls = URL.matcher(item.prompt()).results()
                    .map(match -> stripTrailingPunctuation(match.group()))
                    .toList();
            String strongest = urls.stream().anyMatch(registry::isOfficialUrl) ? "official"
                    : urls.stream().anyMatch(registry::isTrustedMediaUrl) ? "media" : "community";
            if (!strongest.equals(item.gold().sourceTier())) driftedCases.add(item.id());
        }
        // This payload is frozen and human-approved, so do not rewrite it in
        // place. These four rows encode the former whole-host Hugging Face
        // policy; runtime now correctly trusts only official HF URL prefixes.
        assertEquals(Set.of(
                "holdout-015-official-harbor-embed-zh",
                "holdout-016-official-harbor-embed-en",
                "holdout-045-external-citation-spruce-embedding-zh",
                "holdout-046-external-citation-spruce-embedding-en"
        ), driftedCases, "unexpected source-registry drift in frozen holdout v1");
    }

    private static String stripTrailingPunctuation(String value) {
        return value == null ? "" : value.replaceFirst("[),.;。！!？?]+$", "");
    }

    private static AiNewsLiveAgentBenchmarkRunner.LiveBenchmark readBenchmark() throws Exception {
        try (InputStream input = AiNewsHoldoutBenchmarkDatasetTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(input, "holdout benchmark resource must be packaged");
            return new ObjectMapper().readValue(input, AiNewsLiveAgentBenchmarkRunner.LiveBenchmark.class);
        }
    }
}

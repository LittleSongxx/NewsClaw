package vip.newsclaw.news.evaluation;

import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsDiscoverySearchService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsDiscoveryStabilityEvaluatorTest {

    private final AiNewsDiscoveryStabilityEvaluator evaluator =
            new AiNewsDiscoveryStabilityEvaluator();

    @Test
    void identicalThreeRunSentinelIsEligibleAndScoresOne() {
        List<String> urls = List.of("https://a.example/1", "https://b.example/2",
                "https://c.example/3");

        var report = evaluator.evaluate(List.of(
                batch(1L, urls, false, "a"), batch(2L, urls, false, "a"),
                batch(3L, urls, false, "a")));

        assertTrue(report.liveSentinelEligible());
        assertTrue(report.allRunsUncached());
        assertEquals(1.0D, report.jaccardAt10().mean(), 1.0E-12);
        assertEquals(1.0D, report.jaccardAt30().mean(), 1.0E-12);
        assertEquals(1.0D, report.rboAt10().mean(), 1.0E-12);
        assertEquals(1.0D, report.rboAt30().mean(), 1.0E-12);
        assertEquals(1.0D, report.identicalSnapshotPairRate(), 1.0E-12);
        assertEquals(0, report.outsideWindowAdmittedCount());
    }

    @Test
    void reportsSetOverlapAndRefusesCachedRunsAsLiveSentinel() {
        var report = evaluator.evaluate(List.of(
                batch(1L, List.of("https://a.example/1", "https://b.example/2",
                        "https://c.example/3"), false, "a"),
                batch(2L, List.of("https://a.example/1", "https://c.example/3",
                        "https://d.example/4"), true, "b")));

        assertEquals(0.5D, report.jaccardAt10().mean(), 1.0E-12);
        assertTrue(report.rboAt10().mean() > 0.0D && report.rboAt10().mean() < 1.0D);
        assertFalse(report.liveSentinelEligible());
        assertFalse(report.allRunsUncached());
        assertEquals(0.0D, report.identicalSnapshotPairRate(), 1.0E-12);
    }

    @Test
    void rejectsMixedWindowsAndPolicies() {
        var first = batch(1L, List.of("https://a.example/1"), false, "a");
        var mixedPolicy = new AiNewsDiscoverySearchService.DiscoveryBatch(
                first.mode(), first.evidenceEligible(), first.windowStart(), first.windowEnd(),
                first.queryCount(), first.uniqueUrlCount(), first.candidates(), first.executions(),
                first.structuredSourceCount(), first.message(), first.observedAt(), "other-policy",
                first.snapshotHash(), first.rankingHash(), first.diagnostics(), first.querySnapshots(),
                2L, true);

        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(List.of(first, mixedPolicy)));
    }

    private static AiNewsDiscoverySearchService.DiscoveryBatch batch(Long runId,
                                                                     List<String> urls,
                                                                     boolean cached,
                                                                     String hashSeed) {
        List<AiNewsDiscoverySearchService.DiscoveryCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < urls.size(); index++) {
            candidates.add(new AiNewsDiscoverySearchService.DiscoveryCandidate(index + 1,
                    "candidate " + index, urls.get(index), "example", "2026-08-27T10:00:00Z",
                    0.1D, 0.8D, false, true, List.of("media"), "snippet",
                    AiNewsDiscoverySearchService.TemporalStatus.IN_WINDOW,
                    "media", "current_media"));
        }
        var execution = new AiNewsDiscoverySearchService.QueryExecution("media", "tavily",
                urls.size(), "", "news", LocalDate.parse("2026-08-26"),
                LocalDate.parse("2026-08-29"), List.of(), cached, hashSeed.repeat(64));
        return new AiNewsDiscoverySearchService.DiscoveryBatch(
                "untrusted_fused_news_candidates", false,
                "2026-08-27T02:00:00Z", "2026-08-28T02:00:00Z",
                1, urls.size(), candidates, List.of(execution), 0, "capture required",
                "2026-08-28T01:00:00Z", "policy-v2", hashSeed.repeat(64),
                (hashSeed + "r").repeat(32), Map.of("selectedCandidates", urls.size()),
                List.of(), runId, true);
    }
}

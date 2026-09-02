package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsEventClusterScorer;
import vip.newsclaw.news.service.AiNewsEventClusteringProperties;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Emits the frozen, production-scorer clustering replay artifact. */
class AiNewsEventClusteringReplayEvaluationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Test
    @DisplayName("frozen AI-news event observations replay the production online scorer")
    void replayFrozenDevelopmentContract() throws Exception {
        AiNewsEventClusteringReplayEvaluator.Dataset dataset;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "evals/ai-news/event-clustering/replay-development-v1.json")) {
            if (input == null) throw new IllegalStateException("clustering replay dataset is missing");
            dataset = mapper.readValue(input, AiNewsEventClusteringReplayEvaluator.Dataset.class);
        }

        AiNewsEventClusterScorer scorer = new AiNewsEventClusterScorer(
                new AiNewsEventClusteringProperties());
        AiNewsEventClusteringReplayEvaluator.Report report =
                new AiNewsEventClusteringReplayEvaluator(scorer).evaluate(dataset,
                        System.getProperty("git.commit", "unknown"),
                        "./scripts/eval-ai-news-event-clustering.sh");

        assertEquals(20L, report.counts().get("observations"));
        assertEquals(2L, report.counts().get("replayOrders"));
        assertEquals(dataset.expectedConfigHash(), report.configHash(),
                "feature/config changes must deliberately rotate the frozen provenance hash");
        assertEquals(List.of("observations", "goldClusters", "predictedClusters", "automaticLinks",
                        "reviewProposals", "replayOrders"),
                new ArrayList<>(report.counts().keySet()),
                "content-addressed reports require deterministic count ordering");
        assertEquals(report.replays().getFirst().caseIds(),
                new ArrayList<>(report.replays().getFirst().assignments().keySet()),
                "replay assignments must retain arrival order");
        assertEquals(dataset.gates().keySet().stream().sorted().toList(),
                new ArrayList<>(report.gates().keySet()),
                "gate ordering must be deterministic");
        assertTrue(report.passed(), () -> "clustering replay gate failed: " + report.badcases());
        assertFalse(report.badcases().stream()
                .anyMatch(item -> "incorrect-auto-link".equals(item.kind())));

        write(System.getProperty("ai.news.clustering.manifest"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        write(System.getProperty("ai.news.clustering.markdown"),
                AiNewsEventClusteringReplayEvaluator.toMarkdown(report));
        System.out.printf("AI_NEWS_CLUSTERING_REPLAY dataset=%s@%s observations=%d "
                        + "autoPrecision=%.6f assistedRecall=%.6f pairwiseF1=%.6f passed=%s%n",
                report.datasetId(), report.datasetVersion(), report.counts().get("observations"),
                report.metrics().get("decision.autoLinkPrecision").value(),
                report.metrics().get("decision.assistedDuplicateRecall").value(),
                report.metrics().get("clustering.pairwiseF1").value(), report.passed());
    }

    private static void write(String target, String content) throws Exception {
        if (target == null || target.isBlank()) return;
        Path path = Path.of(target).toAbsolutePath().normalize();
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}

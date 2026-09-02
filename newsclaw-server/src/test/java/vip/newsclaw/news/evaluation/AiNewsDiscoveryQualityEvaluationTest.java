package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Scores a caller-supplied, independently adjudicated AI-news discovery run. */
class AiNewsDiscoveryQualityEvaluationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    @DisplayName("AI-news discovery dataset emits the P0 vertical-quality manifest")
    void scoreSuppliedDiscoveryDataset() throws Exception {
        String input = System.getProperty("ai.news.discovery.input");
        Assumptions.assumeTrue(input != null && !input.isBlank(),
                "set -Dai.news.discovery.input=/path/to/adjudicated-discovery-dataset.json");
        Path inputPath = Path.of(input).toAbsolutePath().normalize();
        byte[] inputBytes = Files.readAllBytes(inputPath);
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset supplied = mapper.readValue(inputBytes,
                AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);
        Map<String, String> executionMetadata = new LinkedHashMap<>(supplied.executionMetadata());
        executionMetadata.put("evaluationTree",
                System.getProperty("ai.news.discovery.evaluation-tree", "unknown"));
        executionMetadata.put("datasetSha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(inputBytes)));
        executionMetadata.put("datasetPath", inputPath.toString());
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset =
                new AiNewsDiscoveryQualityEvaluator.DiscoveryDataset(
                        supplied.schemaVersion(), supplied.datasetId(), supplied.datasetVersion(),
                        supplied.evaluationScope(), supplied.window(), supplied.config(), executionMetadata,
                        supplied.goldEvents(), supplied.discoveryCandidates(), supplied.systemEvents(), supplied.clusterAssignments(),
                        supplied.rankingSnapshots(), supplied.limitations());

        AiNewsDiscoveryQualityEvaluator.EvaluationReport report =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset,
                        System.getProperty("git.commit", "unknown"),
                        "./scripts/eval-ai-news-discovery-quality.sh <dataset.json>");
        assertNotNull(report.manifest());

        write(System.getProperty("ai.news.discovery.manifest"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.manifest()));
        write(System.getProperty("ai.news.discovery.markdown"),
                AiNewsDiscoveryQualityReportRenderer.toMarkdown(report.manifest()));
        System.out.printf("AI_NEWS_DISCOVERY_EVAL dataset=%s@%s gold=%d output=%d p0Complete=%s badcases=%d%n",
                report.manifest().datasetId(), report.manifest().datasetVersion(),
                report.manifest().counts().get("goldEvents"),
                report.manifest().counts().get("outputWindowEvents"),
                report.manifest().p0Complete(), report.badcases().size());
    }

    private static void write(String target, String content) throws Exception {
        if (target == null || target.isBlank()) return;
        Path path = Path.of(target).toAbsolutePath().normalize();
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}

package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Scores a human-labeled real Agent trace dataset supplied by the caller.
 * The test is skipped in the default suite because a repository must not ship
 * personal prompts, source snapshots, or channel identifiers as fixtures.
 */
class AiNewsTraceQualityEvaluationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("human-labeled AI-news traces emit a comparable quality manifest")
    void scoreSuppliedTraceDataset() throws Exception {
        String input = System.getProperty("ai.news.quality.input");
        Assumptions.assumeTrue(input != null && !input.isBlank(),
                "set -Dai.news.quality.input=/path/to/labeled-traces.json to score sampled Agent traces");
        Path inputPath = Path.of(input).toAbsolutePath();
        AiNewsQualityEvaluator.QualityDataset inputDataset = mapper.readValue(Files.readString(inputPath),
                AiNewsQualityEvaluator.QualityDataset.class);
        Map<String, String> executionMetadata = new LinkedHashMap<>(inputDataset.executionMetadata());
        executionMetadata.put("evaluationTree",
                System.getProperty("ai.news.quality.evaluation-tree", "unknown"));
        AiNewsQualityEvaluator.QualityDataset dataset = new AiNewsQualityEvaluator.QualityDataset(
                inputDataset.datasetId(), inputDataset.datasetVersion(), inputDataset.evaluationScope(),
                executionMetadata, inputDataset.cases(), inputDataset.limitations());
        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(dataset,
                System.getProperty("git.commit", "unknown"),
                "mvn -pl newsclaw-server -am -Dtest=AiNewsTraceQualityEvaluationTest test");
        assertNotNull(report.manifest());

        write(System.getProperty("ai.news.quality.manifest"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.manifest()));
        write(System.getProperty("ai.news.quality.markdown"), AiNewsQualityReportRenderer.toMarkdown(report.manifest()));
        System.out.printf("AI_NEWS_TRACE_QUALITY_EVAL dataset=%s@%s cases=%d badcases=%d%n",
                report.manifest().datasetId(), report.manifest().datasetVersion(),
                report.manifest().caseCounts().get("total"), report.badcases().size());
    }

    private static void write(String target, String content) throws Exception {
        if (target == null || target.isBlank()) return;
        Path path = Path.of(target).toAbsolutePath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}

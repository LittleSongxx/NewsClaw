package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Emits the versioned evidence manifest consumed by scripts/eval-ai-news-evidence.sh. */
class AiNewsEvidenceManifestEvaluationTest {

    @Test
    void deterministicEvidenceManifestHasNoUnexpectedBadcases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<AiNewsPolicyEvaluator.EvidenceCase> cases;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("evals/ai-news/evidence-manifest-cases.json")) {
            cases = mapper.readValue(input, new TypeReference<>() { });
        }
        String commit = System.getProperty("git.commit", "unknown");
        String command = "mvn -pl newsclaw-server -am -Dtest=AiNewsEvidenceManifestEvaluationTest test";
        AiNewsPolicyEvaluator.EvaluationReport report = new AiNewsPolicyEvaluator(
                new AiNewsSourceRegistry()).evaluate(cases, commit, command);
        assertTrue(report.passed(), () -> "unexpected evidence badcases: " + report.manifest().badcases());
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.manifest());
        System.out.println("AI_NEWS_EVIDENCE_MANIFEST " + json);
        String output = System.getProperty("ai.news.eval.manifest");
        if (output != null && !output.isBlank()) {
            Path path = Path.of(output);
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, json);
        }
    }
}

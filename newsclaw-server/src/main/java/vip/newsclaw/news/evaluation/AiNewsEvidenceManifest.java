package vip.newsclaw.news.evaluation;

import java.util.List;
import java.util.Map;

/** Machine-readable output of the deterministic AI-news policy evaluation. */
public record AiNewsEvidenceManifest(
        String schemaVersion,
        String evaluationScope,
        String generatedAt,
        String gitCommit,
        Map<String, Integer> caseCounts,
        Map<String, Double> metrics,
        List<Badcase> badcases,
        String testCommand
) {
    public AiNewsEvidenceManifest {
        caseCounts = caseCounts == null ? Map.of() : Map.copyOf(caseCounts);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        badcases = badcases == null ? List.of() : List.copyOf(badcases);
    }

    public record Badcase(String caseId, String rule, String expected, String actual, String detail) {
    }
}

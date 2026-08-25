package vip.newsclaw.news.evaluation;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/** Renders a concise human-readable companion for an AI-news quality manifest. */
public final class AiNewsQualityReportRenderer {

    private AiNewsQualityReportRenderer() {
    }

    public static String toMarkdown(AiNewsQualityEvaluator.AiNewsQualityManifest manifest) {
        StringBuilder out = new StringBuilder();
        out.append("# AI News Quality Evaluation\n\n");
        out.append("- Scope: `").append(manifest.evaluationScope()).append("`\n");
        out.append("- Dataset: `").append(manifest.datasetId()).append("@")
                .append(manifest.datasetVersion()).append("`\n");
        out.append("- Git commit: `").append(manifest.gitCommit()).append("`\n");
        out.append("- Generated at: `").append(manifest.generatedAt()).append("`\n");
        out.append("- Cases: `").append(manifest.caseCounts().getOrDefault("total", 0L)).append("`\n\n");

        if (!manifest.executionMetadata().isEmpty()) {
            out.append("## Execution Context\n\n");
            manifest.executionMetadata().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> out.append("- ").append(entry.getKey()).append(": `")
                            .append(entry.getValue()).append("`\n"));
            out.append('\n');
        }

        out.append("## Metrics\n\n");
        out.append("| Metric | N | Value | Precision | Recall | F1 |\n");
        out.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        manifest.metrics().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendMetric(out, entry.getKey(), entry.getValue()));
        out.append('\n');

        if (!manifest.slices().isEmpty()) {
            out.append("## Slice Coverage\n\n");
            out.append("| Slice | Cases | Verification F1 | Refusal F1 | Citation-block F1 |\n");
            out.append("| --- | ---: | ---: | ---: | ---: |\n");
            manifest.slices().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        Map<String, AiNewsQualityEvaluator.MetricSummary> metrics = entry.getValue().metrics();
                        out.append("| `").append(entry.getKey()).append("` | ")
                                .append(entry.getValue().cases()).append(" | ")
                                .append(format(metrics.get("verificationEligible").f1())).append(" | ")
                                .append(format(metrics.get("properRefusal").f1())).append(" | ")
                                .append(format(metrics.get("citationViolationBlocked").f1())).append(" |\n");
                    });
            out.append('\n');
        }

        out.append("## Badcases\n\n");
        if (manifest.badcases().isEmpty()) {
            out.append("No mismatches against the labeled dataset.\n\n");
        } else {
            out.append("| Case | Metric | Expected | Actual | Detail |\n");
            out.append("| --- | --- | --- | --- | --- |\n");
            manifest.badcases().stream()
                    .sorted(Comparator.comparing(AiNewsQualityEvaluator.Badcase::caseId)
                            .thenComparing(AiNewsQualityEvaluator.Badcase::metric))
                    .forEach(item -> out.append("| `").append(item.caseId()).append("` | `")
                            .append(item.metric()).append("` | ").append(escape(item.expected()))
                            .append(" | ").append(escape(item.actual())).append(" | ")
                            .append(escape(item.detail())).append(" |\n"));
            out.append('\n');
        }

        out.append("## Boundaries\n\n");
        if (manifest.limitations().isEmpty()) {
            out.append("No boundary notes were supplied.\n");
        } else {
            manifest.limitations().forEach(item -> out.append("- ").append(item).append('\n'));
        }
        out.append("\nTest command: `").append(manifest.testCommand()).append("`\n");
        return out.toString();
    }

    private static void appendMetric(StringBuilder out, String name,
                                     AiNewsQualityEvaluator.MetricSummary metric) {
        out.append("| `").append(name).append("` | ").append(metric.evaluated()).append(" | ")
                .append(format(metric.value())).append(" | ").append(format(metric.precision()))
                .append(" | ").append(format(metric.recall())).append(" | ")
                .append(format(metric.f1())).append(" |\n");
    }

    private static String format(Double value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.4f", value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }
}

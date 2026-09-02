package vip.newsclaw.news.evaluation;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/** Renders an auditable Markdown companion for an AI-news discovery manifest. */
public final class AiNewsDiscoveryQualityReportRenderer {

    private AiNewsDiscoveryQualityReportRenderer() {
    }

    public static String toMarkdown(AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest) {
        StringBuilder out = new StringBuilder();
        out.append("# AI News Discovery P0 Evaluation\n\n");
        out.append("- P0 complete: `").append(manifest.p0Complete()).append("`\n");
        out.append("- Evaluation eligible: `").append(manifest.evaluationEligible()).append("`\n");
        out.append("- Scope: `").append(escape(manifest.evaluationScope())).append("`\n");
        out.append("- Dataset: `").append(escape(manifest.datasetId())).append("@")
                .append(escape(manifest.datasetVersion())).append("`\n");
        out.append("- Git commit: `").append(escape(manifest.gitCommit())).append("`\n");
        out.append("- Gold window: `").append(manifest.window().startAt()).append("` to `")
                .append(manifest.window().endAt()).append("`\n");
        out.append("- Observation end: `").append(manifest.window().observationEndAt()).append("`\n");
        out.append("- Generated at: `").append(manifest.generatedAt()).append("`\n\n");

        out.append("## Counts\n\n");
        out.append("| Count | Value |\n");
        out.append("| --- | ---: |\n");
        manifest.counts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.append("| `").append(escape(entry.getKey())).append("` | ")
                        .append(entry.getValue()).append(" |\n"));
        out.append('\n');

        out.append("## P0 Metrics\n\n");
        out.append("| Metric | Value (95% CI) | Numerator | Denominator | Unit | Method | Warnings |\n");
        out.append("| --- | ---: | ---: | ---: | --- | --- | --- |\n");
        manifest.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendMetric(out, entry.getKey(), entry.getValue()));
        out.append('\n');

        if (!manifest.slices().isEmpty()) {
            out.append("## Slice Recall\n\n");
            out.append("| Slice | Gold N | Event recall | Evidence-ready recall | Importance-weighted recall | Freshness recalls | Warnings |\n");
            out.append("| --- | ---: | ---: | ---: | ---: | --- | --- |\n");
            manifest.slices().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        Map<String, AiNewsDiscoveryQualityEvaluator.MetricSummary> metrics = entry.getValue().metrics();
                        String freshness = metrics.entrySet().stream()
                                .filter(metric -> metric.getKey().startsWith("recallAt")
                                        || metric.getKey().startsWith("evidenceReadyRecallAt"))
                                .sorted(Map.Entry.comparingByKey())
                                .map(metric -> metric.getKey() + "=" + format(metric.getValue().value()))
                                .reduce((left, right) -> left + "; " + right).orElse("n/a");
                        out.append("| `").append(escape(entry.getKey())).append("` | ")
                                .append(entry.getValue().goldEvents()).append(" | ")
                                .append(formatMetric(metrics.get("eventRecall"))).append(" | ")
                                .append(formatMetric(metrics.get("evidenceReadyEventRecall"))).append(" | ")
                                .append(formatMetric(metrics.get("importanceWeightedRecall"))).append(" | ")
                                .append(escape(freshness)).append(" | ")
                                .append(escape(String.join("; ", entry.getValue().warnings()))).append(" |\n");
                    });
            out.append('\n');
        }

        if (!manifest.rankingSnapshots().isEmpty()) {
            out.append("## Ranking Snapshots\n\n");
            out.append("| Snapshot | At | Eligible gold | Returned | Scores |\n");
            out.append("| --- | --- | ---: | ---: | --- |\n");
            manifest.rankingSnapshots().stream()
                    .sorted(Comparator.comparing(AiNewsDiscoveryQualityEvaluator.RankingSnapshotSummary::at)
                            .thenComparing(AiNewsDiscoveryQualityEvaluator.RankingSnapshotSummary::snapshotId))
                    .forEach(snapshot -> {
                        String scores = snapshot.metrics().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(entry -> entry.getKey() + "=" + format(entry.getValue().value()))
                                .reduce((left, right) -> left + "; " + right).orElse("n/a");
                        out.append("| `").append(escape(snapshot.snapshotId())).append("` | `")
                                .append(snapshot.at()).append("` | ")
                                .append(snapshot.eligibleGoldEvents()).append(" | ")
                                .append(snapshot.returnedItems()).append(" | ")
                                .append(escape(scores)).append(" |\n");
                    });
            out.append('\n');
        }

        out.append("## Badcases\n\n");
        if (manifest.badcases().isEmpty()) {
            out.append("No scored quality defects.\n\n");
        } else {
            out.append("| Kind | ID | Label | Detail |\n");
            out.append("| --- | --- | --- | --- |\n");
            manifest.badcases().stream()
                    .sorted(Comparator.comparing(AiNewsDiscoveryQualityEvaluator.Badcase::kind)
                            .thenComparing(AiNewsDiscoveryQualityEvaluator.Badcase::id))
                    .forEach(item -> out.append("| `").append(escape(item.kind())).append("` | `")
                            .append(escape(item.id())).append("` | ").append(escape(item.label()))
                            .append(" | ").append(escape(item.detail())).append(" |\n"));
            out.append('\n');
        }

        if (!manifest.executionMetadata().isEmpty()) {
            out.append("## Execution Context\n\n");
            manifest.executionMetadata().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> out.append("- ").append(escape(entry.getKey())).append(": `")
                            .append(escape(entry.getValue())).append("`\n"));
            out.append('\n');
        }

        if (!manifest.warnings().isEmpty()) {
            out.append("## Report Warnings\n\n");
            manifest.warnings().forEach(warning -> out.append("- ").append(escape(warning)).append("\n"));
            out.append('\n');
        }
        if (!manifest.limitations().isEmpty()) {
            out.append("## Limitations\n\n");
            manifest.limitations().forEach(item -> out.append("- ").append(escape(item)).append("\n"));
            out.append('\n');
        }

        out.append("## Method References\n\n");
        manifest.methodReferences().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.append("- ").append(escape(entry.getKey())).append(": ")
                        .append(entry.getValue()).append("\n"));
        out.append("\nTest command: `").append(escape(manifest.testCommand())).append("`\n");
        return out.toString();
    }

    private static void appendMetric(StringBuilder out,
                                     String name,
                                     AiNewsDiscoveryQualityEvaluator.MetricSummary metric) {
        out.append("| `").append(escape(name)).append("` | ")
                .append(formatMetric(metric)).append(" | ")
                .append(metric.numerator() == null ? "n/a" : metric.numerator()).append(" | ")
                .append(metric.denominator() == null ? "n/a" : metric.denominator()).append(" | ")
                .append(escape(metric.unit())).append(" | ")
                .append(escape(metric.method())).append(" | ")
                .append(escape(String.join("; ", metric.warnings()))).append(" |\n");
    }

    private static String formatMetric(AiNewsDiscoveryQualityEvaluator.MetricSummary metric) {
        if (metric == null || metric.value() == null) return "n/a";
        String value = format(metric.value());
        if (metric.confidenceLower() == null || metric.confidenceUpper() == null) return value;
        return value + " [" + format(metric.confidenceLower()) + ", "
                + format(metric.confidenceUpper()) + "]";
    }

    private static String format(Double value) {
        if (value == null || !Double.isFinite(value)) return "n/a";
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\n", " ").replace("`", "'");
    }
}

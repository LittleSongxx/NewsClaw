package vip.newsclaw.news.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Scores a versioned, labeled AI-news evaluation dataset.
 *
 * <p>The evaluator deliberately consumes gold labels and predictions rather
 * than calling a model. This keeps the measurement reproducible and lets the
 * same scorer be used by deterministic policy fixtures, frozen controlled
 * live-Agent cases, and human-reviewed sampled traces. A score is only
 * meaningful within the label provenance and dataset scope recorded in its
 * manifest; it must not be presented as a general online accuracy claim.</p>
 */
public final class AiNewsQualityEvaluator {

    private static final List<String> SOURCE_TIERS = List.of("official", "media", "community");

    public EvaluationReport evaluate(QualityDataset dataset, String gitCommit, String testCommand) {
        QualityDataset input = dataset == null ? QualityDataset.empty() : dataset;
        List<QualityCase> cases = input.cases();
        validate(cases);

        List<Badcase> badcases = new ArrayList<>();
        Score score = score(cases, badcases);
        Map<String, SliceSummary> slices = scoreSlices(cases);

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("total", (long) cases.size());
        counts.put("sourceTierLabeled", (long) score.sourceTierCases());
        counts.put("verificationLabeled", score.verificationEligible().evaluated());
        counts.put("citationLabeled", score.citationViolationBlocked().evaluated());
        counts.put("claimQuoteLabeled", score.claimQuoteSupported().evaluated());
        counts.put("taskSuccessLabeled", score.taskSuccess().evaluated());
        counts.put("toolSelectionLabeled", score.toolSelectionCorrect().evaluated());
        counts.put("toolParametersLabeled", score.toolParametersCorrect().evaluated());
        counts.put("humanReviewLabeled", score.humanReviewRouting().evaluated());
        counts.put("canonicalizationLabeled", (long) score.canonicalCaseCount());
        counts.put("canonicalPairLabeled", score.canonicalPairwise().evaluated());
        counts.put("properRefusalLabeled", score.properRefusal().evaluated());
        counts.put("unresolvedConflictCases", (long) score.unresolvedConflictCases());
        counts.put("badcases", (long) badcases.size());

        AiNewsQualityManifest manifest = new AiNewsQualityManifest(
                "1.0",
                defaultValue(input.evaluationScope(), "unspecified"),
                defaultValue(input.datasetId(), "unnamed-dataset"),
                defaultValue(input.datasetVersion(), "unknown"),
                Instant.now().toString(),
                defaultValue(gitCommit, "unknown"),
                input.executionMetadata(),
                counts,
                score.metrics(),
                slices,
                badcases,
                input.limitations(),
                defaultValue(testCommand, "unspecified"));
        return new EvaluationReport(manifest, List.copyOf(badcases));
    }

    private static void validate(List<QualityCase> cases) {
        Set<String> ids = new LinkedHashSet<>();
        for (QualityCase item : cases) {
            if (item == null || item.id().isBlank()) {
                throw new IllegalArgumentException("AI news quality case id must not be blank");
            }
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException("duplicate AI news quality case id: " + item.id());
            }
            GoldLabel gold = item.gold();
            Prediction prediction = item.prediction();
            if (gold.sourceTier() != null && prediction.sourceTier() == null) {
                throw missingPrediction(item.id(), "sourceTier");
            }
            if (gold.verificationEligible() != null && prediction.verificationEligible() == null) {
                throw missingPrediction(item.id(), "verificationEligible");
            }
            if (gold.citationAllowed() != null && prediction.citationAllowed() == null) {
                throw missingPrediction(item.id(), "citationAllowed");
            }
            if (gold.claimQuoteSupported() != null && prediction.claimQuoteSupported() == null) {
                throw missingPrediction(item.id(), "claimQuoteSupported");
            }
            if (gold.taskSucceeded() != null && prediction.taskSucceeded() == null) {
                throw missingPrediction(item.id(), "taskSucceeded");
            }
            if (gold.toolSelectionCorrect() != null && prediction.toolSelectionCorrect() == null) {
                throw missingPrediction(item.id(), "toolSelectionCorrect");
            }
            if (gold.toolParametersCorrect() != null && prediction.toolParametersCorrect() == null) {
                throw missingPrediction(item.id(), "toolParametersCorrect");
            }
            if (gold.humanReviewRequired() != null && prediction.humanReviewRequested() == null) {
                throw missingPrediction(item.id(), "humanReviewRequested");
            }
            if (gold.canonicalGroup() != null && prediction.canonicalGroup() == null) {
                throw missingPrediction(item.id(), "canonicalGroup");
            }
            if ((gold.refusalRequired() != null || Boolean.TRUE.equals(gold.unresolvedConflict()))
                    && prediction.verificationEligible() == null) {
                throw missingPrediction(item.id(), "verificationEligible");
            }
        }
    }

    private static IllegalArgumentException missingPrediction(String caseId, String field) {
        return new IllegalArgumentException("quality case '" + caseId
                + "' has a gold label for " + field + " but no prediction");
    }

    private static Map<String, SliceSummary> scoreSlices(List<QualityCase> cases) {
        Map<String, List<QualityCase>> bySlice = new LinkedHashMap<>();
        for (QualityCase item : cases) {
            item.slices().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> bySlice.computeIfAbsent(entry.getKey() + "=" + entry.getValue(),
                            ignored -> new ArrayList<>()).add(item));
        }
        Map<String, SliceSummary> summaries = new LinkedHashMap<>();
        bySlice.forEach((slice, items) -> {
            Score score = score(items, null);
            summaries.put(slice, new SliceSummary(items.size(), score.metrics()));
        });
        return Map.copyOf(summaries);
    }

    private static Score score(List<QualityCase> cases, List<Badcase> badcases) {
        List<SourceTierPair> sourceTierPairs = new ArrayList<>();
        BinaryCounter verificationEligible = new BinaryCounter();
        BinaryCounter citationViolationBlocked = new BinaryCounter();
        BinaryCounter claimQuoteSupported = new BinaryCounter();
        BinaryCounter taskSuccess = new BinaryCounter();
        BinaryCounter toolSelectionCorrect = new BinaryCounter();
        BinaryCounter toolParametersCorrect = new BinaryCounter();
        BinaryCounter humanReviewRouting = new BinaryCounter();
        BinaryCounter properRefusal = new BinaryCounter();
        BinaryCounter canonicalPairwise = new BinaryCounter();
        List<QualityCase> canonicalCases = new ArrayList<>();
        int unresolvedConflictCases = 0;
        int unresolvedConflictBlocked = 0;

        for (QualityCase item : cases) {
            GoldLabel gold = item.gold();
            Prediction prediction = item.prediction();
            if (gold.sourceTier() != null) {
                String expected = normalizeTier(gold.sourceTier());
                String actual = normalizeTier(prediction.sourceTier());
                sourceTierPairs.add(new SourceTierPair(expected, actual));
                addBadcaseIfDifferent(badcases, item, "source-tier", expected, actual,
                        "source registry classification");
            }
            if (gold.verificationEligible() != null) {
                boolean expected = gold.verificationEligible();
                boolean actual = prediction.verificationEligible();
                verificationEligible.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "verification-eligible", expected, actual,
                        "official/corroboration/conflict admission policy");
            }
            if (gold.citationAllowed() != null) {
                boolean expectedAllowed = gold.citationAllowed();
                boolean actualAllowed = prediction.citationAllowed();
                citationViolationBlocked.add(!expectedAllowed, !actualAllowed);
                addBadcaseIfDifferent(badcases, item, "citation-boundary", expectedAllowed, actualAllowed,
                        "only evidence-packet citations are permitted");
            }
            if (gold.claimQuoteSupported() != null) {
                boolean expected = gold.claimQuoteSupported();
                boolean actual = prediction.claimQuoteSupported();
                claimQuoteSupported.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "claim-quote-support", expected, actual,
                        "labeled claim-to-quote support");
            }
            if (gold.taskSucceeded() != null) {
                boolean expected = gold.taskSucceeded();
                boolean actual = prediction.taskSucceeded();
                taskSuccess.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "task-success", expected, actual,
                        "labeled end-to-end task outcome");
            }
            if (gold.toolSelectionCorrect() != null) {
                boolean expected = gold.toolSelectionCorrect();
                boolean actual = prediction.toolSelectionCorrect();
                toolSelectionCorrect.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "tool-selection", expected, actual,
                        "labeled tool choice and execution order");
            }
            if (gold.toolParametersCorrect() != null) {
                boolean expected = gold.toolParametersCorrect();
                boolean actual = prediction.toolParametersCorrect();
                toolParametersCorrect.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "tool-parameters", expected, actual,
                        "labeled tool argument correctness");
            }
            if (gold.humanReviewRequired() != null) {
                boolean expected = gold.humanReviewRequired();
                boolean actual = prediction.humanReviewRequested();
                humanReviewRouting.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "human-review-routing", expected, actual,
                        "high-risk or unresolved work must be routed for review");
            }
            if (gold.refusalRequired() != null) {
                boolean expected = gold.refusalRequired();
                boolean actual = !prediction.verificationEligible();
                properRefusal.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "proper-refusal", expected, actual,
                        "insufficient or unsafe evidence must not enter production");
            }
            if (gold.canonicalGroup() != null) {
                canonicalCases.add(item);
                addBadcaseIfDifferent(badcases, item, "canonical-url", gold.canonicalGroup(),
                        prediction.canonicalGroup(), "canonical URL used for event deduplication");
            }
            if (Boolean.TRUE.equals(gold.unresolvedConflict())) {
                unresolvedConflictCases++;
                if (!prediction.verificationEligible()) {
                    unresolvedConflictBlocked++;
                } else if (badcases != null) {
                    badcases.add(Badcase.of(item, "unresolved-conflict-block", "blocked", "allowed",
                            "an unresolved conflict must prevent verification"));
                }
            }
        }

        for (int left = 0; left < canonicalCases.size(); left++) {
            QualityCase a = canonicalCases.get(left);
            for (int right = left + 1; right < canonicalCases.size(); right++) {
                QualityCase b = canonicalCases.get(right);
                boolean expectedSame = Objects.equals(a.gold().canonicalGroup(), b.gold().canonicalGroup());
                boolean actualSame = Objects.equals(a.prediction().canonicalGroup(), b.prediction().canonicalGroup());
                canonicalPairwise.add(expectedSame, actualSame);
            }
        }

        Map<String, MetricSummary> metrics = new LinkedHashMap<>();
        addSourceTierMetrics(metrics, sourceTierPairs);
        metrics.put("verificationEligible", verificationEligible.summary());
        metrics.put("properRefusal", properRefusal.summary());
        metrics.put("citationViolationBlocked", citationViolationBlocked.summary());
        metrics.put("claimQuoteSupported", claimQuoteSupported.summary());
        metrics.put("taskSuccess", taskSuccess.summary());
        metrics.put("toolSelectionCorrect", toolSelectionCorrect.summary());
        metrics.put("toolParametersCorrect", toolParametersCorrect.summary());
        metrics.put("humanReviewRouting", humanReviewRouting.summary());
        metrics.put("canonicalDedup.pairwise", canonicalPairwise.summary());
        metrics.put("unresolvedConflict.blockRate", MetricSummary.rate(
                unresolvedConflictCases, unresolvedConflictBlocked));
        return new Score(sourceTierPairs.size(), verificationEligible, citationViolationBlocked,
                claimQuoteSupported, taskSuccess, toolSelectionCorrect, toolParametersCorrect,
                humanReviewRouting, properRefusal, canonicalPairwise, canonicalCases.size(),
                unresolvedConflictCases, Map.copyOf(metrics));
    }

    private static void addSourceTierMetrics(Map<String, MetricSummary> metrics,
                                             List<SourceTierPair> pairs) {
        int correct = (int) pairs.stream().filter(pair -> pair.expected().equals(pair.actual())).count();
        metrics.put("sourceTier.accuracy", MetricSummary.rate(pairs.size(), correct));
        List<Double> f1Values = new ArrayList<>();
        for (String tier : SOURCE_TIERS) {
            BinaryCounter counter = new BinaryCounter();
            for (SourceTierPair pair : pairs) {
                counter.add(tier.equals(pair.expected()), tier.equals(pair.actual()));
            }
            MetricSummary summary = counter.summary();
            metrics.put("sourceTier." + tier, summary);
            if (pairs.stream().anyMatch(pair -> tier.equals(pair.expected())) && summary.f1() != null) {
                f1Values.add(summary.f1());
            }
        }
        Double macroF1 = f1Values.isEmpty() ? null
                : f1Values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);
        metrics.put("sourceTier.macroF1", MetricSummary.scalar(pairs.size(), macroF1));
    }

    private static void addBadcaseIfDifferent(List<Badcase> badcases, QualityCase item, String metric,
                                               Object expected, Object actual, String detail) {
        if (badcases != null && !Objects.equals(expected, actual)) {
            badcases.add(Badcase.of(item, metric, String.valueOf(expected), String.valueOf(actual), detail));
        }
    }

    private static String normalizeTier(String value) {
        return defaultValue(value, "missing").trim().toLowerCase(Locale.ROOT);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** A labeled collection of either deterministic fixtures or sampled live traces. */
    public record QualityDataset(String datasetId,
                                 String datasetVersion,
                                 String evaluationScope,
                                 Map<String, String> executionMetadata,
                                 List<QualityCase> cases,
                                 List<String> limitations) {
        public QualityDataset {
            executionMetadata = immutableMap(executionMetadata);
            cases = cases == null ? List.of() : List.copyOf(cases);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }

        static QualityDataset empty() {
            return new QualityDataset("unnamed-dataset", "unknown", "unspecified", Map.of(), List.of(), List.of());
        }
    }

    /** One unit of evaluation. Slices are intentionally low-cardinality labels such as language=zh. */
    public record QualityCase(String id,
                              Map<String, String> slices,
                              GoldLabel gold,
                              Prediction prediction) {
        public QualityCase {
            id = id == null ? "" : id.trim();
            slices = immutableMap(slices);
            gold = gold == null ? GoldLabel.empty() : gold;
            prediction = prediction == null ? Prediction.empty() : prediction;
        }
    }

    /** Human-approved expected result for one case. Null means that metric is out of scope for the case. */
    public record GoldLabel(String sourceTier,
                            Boolean verificationEligible,
                            Boolean citationAllowed,
                            Boolean claimQuoteSupported,
                            String canonicalGroup,
                            Boolean refusalRequired,
                            Boolean unresolvedConflict,
                            Boolean taskSucceeded,
                            Boolean toolSelectionCorrect,
                            Boolean toolParametersCorrect,
                            Boolean humanReviewRequired) {
        static GoldLabel empty() {
            return new GoldLabel(null, null, null, null, null, null, null,
                    null, null, null, null);
        }
    }

    /** System output or manually adjudicated live-trace output for one case. */
    public record Prediction(String sourceTier,
                             Boolean verificationEligible,
                             Boolean citationAllowed,
                             Boolean claimQuoteSupported,
                             String canonicalGroup,
                             Boolean taskSucceeded,
                             Boolean toolSelectionCorrect,
                             Boolean toolParametersCorrect,
                             Boolean humanReviewRequested) {
        static Prediction empty() {
            return new Prediction(null, null, null, null, null, null, null, null, null);
        }
    }

    /** Machine-readable report designed to be stored as a CI or benchmark artifact. */
    public record AiNewsQualityManifest(String schemaVersion,
                                        String evaluationScope,
                                        String datasetId,
                                        String datasetVersion,
                                        String generatedAt,
                                        String gitCommit,
                                        Map<String, String> executionMetadata,
                                        Map<String, Long> caseCounts,
                                        Map<String, MetricSummary> metrics,
                                        Map<String, SliceSummary> slices,
                                        List<Badcase> badcases,
                                        List<String> limitations,
                                        String testCommand) {
        public AiNewsQualityManifest {
            executionMetadata = immutableMap(executionMetadata);
            caseCounts = immutableMap(caseCounts);
            metrics = immutableMap(metrics);
            slices = immutableMap(slices);
            badcases = badcases == null ? List.of() : List.copyOf(badcases);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    /** Precision/recall/F1 use the positive class implied by the metric name. */
    public record MetricSummary(long evaluated,
                                Long correct,
                                Long truePositive,
                                Long falsePositive,
                                Long falseNegative,
                                Long trueNegative,
                                Double value,
                                Double precision,
                                Double recall,
                                Double f1) {
        static MetricSummary rate(long evaluated, long successes) {
            return new MetricSummary(evaluated, successes, null, null, null, null,
                    ratio(successes, evaluated), null, null, null);
        }

        static MetricSummary scalar(long evaluated, Double value) {
            return new MetricSummary(evaluated, null, null, null, null, null,
                    value, null, null, null);
        }
    }

    public record SliceSummary(int cases, Map<String, MetricSummary> metrics) {
        public SliceSummary {
            metrics = immutableMap(metrics);
        }
    }

    public record Badcase(String caseId,
                          String metric,
                          String expected,
                          String actual,
                          String detail,
                          Map<String, String> slices) {
        static Badcase of(QualityCase item, String metric, String expected, String actual, String detail) {
            return new Badcase(item.id(), metric, expected, actual, detail, item.slices());
        }

        public Badcase {
            slices = immutableMap(slices);
        }
    }

    public record EvaluationReport(AiNewsQualityManifest manifest, List<Badcase> badcases) {
    }

    private record SourceTierPair(String expected, String actual) {
    }

    private record Score(int sourceTierCases,
                         BinaryCounter verificationEligible,
                         BinaryCounter citationViolationBlocked,
                         BinaryCounter claimQuoteSupported,
                         BinaryCounter taskSuccess,
                         BinaryCounter toolSelectionCorrect,
                         BinaryCounter toolParametersCorrect,
                         BinaryCounter humanReviewRouting,
                         BinaryCounter properRefusal,
                         BinaryCounter canonicalPairwise,
                         int canonicalCaseCount,
                         int unresolvedConflictCases,
                         Map<String, MetricSummary> metrics) {
    }

    private static final class BinaryCounter {
        private long truePositive;
        private long falsePositive;
        private long falseNegative;
        private long trueNegative;

        void add(boolean expectedPositive, boolean actualPositive) {
            if (expectedPositive && actualPositive) truePositive++;
            else if (!expectedPositive && actualPositive) falsePositive++;
            else if (expectedPositive) falseNegative++;
            else trueNegative++;
        }

        long evaluated() {
            return truePositive + falsePositive + falseNegative + trueNegative;
        }

        MetricSummary summary() {
            long evaluated = evaluated();
            long correct = truePositive + trueNegative;
            Double precision = ratio(truePositive, truePositive + falsePositive);
            Double recall = ratio(truePositive, truePositive + falseNegative);
            Double f1 = ratio(2L * truePositive,
                    2L * truePositive + falsePositive + falseNegative);
            return new MetricSummary(evaluated, correct, truePositive, falsePositive, falseNegative,
                    trueNegative, ratio(correct, evaluated), precision, recall, f1);
        }
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator <= 0 ? null : ((double) numerator) / denominator;
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> input) {
        if (input == null || input.isEmpty()) return Map.of();
        return Map.copyOf(new LinkedHashMap<>(input));
    }
}

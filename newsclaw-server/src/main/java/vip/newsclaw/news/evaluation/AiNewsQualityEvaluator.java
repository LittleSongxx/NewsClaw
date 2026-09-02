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
    private static final int SMALL_SAMPLE_THRESHOLD = 20;
    private static final double WILSON_95_Z = 1.959963984540054D;

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
        counts.put("invalidOutputs", cases.stream()
                .filter(item -> Boolean.FALSE.equals(item.prediction().outputValid())).count());
        counts.put("badcases", (long) badcases.size());

        AiNewsQualityManifest manifest = new AiNewsQualityManifest(
                "2.0",
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
            boolean hasLabels = gold.sourceTier() != null || gold.verificationEligible() != null
                    || gold.citationAllowed() != null || gold.claimQuoteSupported() != null
                    || gold.canonicalGroup() != null || gold.refusalRequired() != null
                    || gold.unresolvedConflict() != null || gold.taskSucceeded() != null
                    || gold.toolSelectionCorrect() != null || gold.toolParametersCorrect() != null
                    || gold.humanReviewRequired() != null;
            if (hasLabels && prediction.outputValid() == null) {
                throw missingPrediction(item.id(), "outputValid");
            }
            boolean explicitlyInvalid = Boolean.FALSE.equals(prediction.outputValid());
            if (gold.sourceTier() != null && prediction.sourceTier() == null && !explicitlyInvalid) {
                throw missingPrediction(item.id(), "sourceTier");
            }
            if (gold.verificationEligible() != null && prediction.verificationEligible() == null
                    && !explicitlyInvalid) {
                throw missingPrediction(item.id(), "verificationEligible");
            }
            if (gold.citationAllowed() != null && prediction.citationAllowed() == null && !explicitlyInvalid) {
                throw missingPrediction(item.id(), "citationAllowed");
            }
            if (gold.claimQuoteSupported() != null && prediction.claimQuoteSupported() == null
                    && !explicitlyInvalid) {
                throw missingPrediction(item.id(), "claimQuoteSupported");
            }
            if (gold.taskSucceeded() != null && prediction.taskSucceeded() == null && !explicitlyInvalid) {
                throw missingPrediction(item.id(), "taskSucceeded");
            }
            if (gold.toolSelectionCorrect() != null && prediction.toolSelectionCorrect() == null
                    && !explicitlyInvalid) {
                throw missingPrediction(item.id(), "toolSelectionCorrect");
            }
            if (gold.toolParametersCorrect() != null && prediction.toolParametersCorrect() == null
                    && !explicitlyInvalid) {
                throw missingPrediction(item.id(), "toolParametersCorrect");
            }
            if (gold.humanReviewRequired() != null && prediction.humanReviewRequested() == null
                    && !explicitlyInvalid) {
                throw missingPrediction(item.id(), "humanReviewRequested");
            }
            if (gold.canonicalGroup() != null && prediction.canonicalGroup() == null && !explicitlyInvalid) {
                throw missingPrediction(item.id(), "canonicalGroup");
            }
            if (gold.refusalRequired() != null && prediction.refusalIssued() == null && !explicitlyInvalid) {
                throw missingPrediction(item.id(), "refusalIssued");
            }
            if (Boolean.TRUE.equals(gold.unresolvedConflict())
                    && prediction.verificationEligible() == null && !explicitlyInvalid) {
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
            summaries.put(slice, new SliceSummary(items.size(), score.metrics(), sliceWarnings(items, score)));
        });
        return Map.copyOf(summaries);
    }

    private static List<String> sliceWarnings(List<QualityCase> cases, Score score) {
        List<String> warnings = new ArrayList<>();
        if (cases.size() < SMALL_SAMPLE_THRESHOLD) {
            warnings.add("small slice: N=" + cases.size() + " < " + SMALL_SAMPLE_THRESHOLD
                    + "; point estimates are unstable");
        }
        boolean singleClass = score.metrics().values().stream()
                .flatMap(metric -> metric.warnings().stream())
                .anyMatch(warning -> warning.startsWith("single-class gold"));
        if (singleClass) {
            warnings.add("one or more binary metrics have single-class gold labels");
        }
        return List.copyOf(warnings);
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
        BinaryCounter unresolvedConflictBlocked = new BinaryCounter();
        List<QualityCase> canonicalCases = new ArrayList<>();
        int unresolvedConflictCases = 0;

        for (QualityCase item : cases) {
            GoldLabel gold = item.gold();
            Prediction prediction = item.prediction();
            if (Boolean.FALSE.equals(prediction.outputValid()) && badcases != null) {
                badcases.add(Badcase.of(item, "output-validity", "true", "false",
                        "prediction was explicitly declared invalid; parseable fields remain independently scored"));
            }
            if (gold.sourceTier() != null) {
                String expected = normalizeTier(gold.sourceTier());
                String actual = normalizeTier(prediction.sourceTier());
                boolean invalid = prediction.sourceTier() == null || !SOURCE_TIERS.contains(actual);
                sourceTierPairs.add(new SourceTierPair(expected, actual, invalid));
                addBadcaseIfDifferent(badcases, item, "source-tier", expected, actual,
                        "source registry classification");
            }
            if (gold.verificationEligible() != null) {
                boolean expected = gold.verificationEligible();
                Boolean actual = prediction.verificationEligible();
                verificationEligible.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "verification-eligible", expected, actual,
                        "official/corroboration/conflict admission policy");
            }
            if (gold.citationAllowed() != null) {
                boolean expectedAllowed = gold.citationAllowed();
                Boolean actualAllowed = prediction.citationAllowed();
                Boolean actualBlocked = actualAllowed == null ? null : !actualAllowed;
                citationViolationBlocked.add(!expectedAllowed, actualBlocked);
                addBadcaseIfDifferent(badcases, item, "citation-boundary", expectedAllowed, actualAllowed,
                        "only evidence-packet citations are permitted");
            }
            if (gold.claimQuoteSupported() != null) {
                boolean expected = gold.claimQuoteSupported();
                Boolean actual = prediction.claimQuoteSupported();
                claimQuoteSupported.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "claim-quote-support", expected, actual,
                        "labeled claim-to-quote support");
            }
            if (gold.taskSucceeded() != null) {
                boolean expected = gold.taskSucceeded();
                Boolean actual = prediction.taskSucceeded();
                taskSuccess.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "task-success", expected, actual,
                        "labeled end-to-end task outcome");
            }
            if (gold.toolSelectionCorrect() != null) {
                boolean expected = gold.toolSelectionCorrect();
                Boolean actual = prediction.toolSelectionCorrect();
                toolSelectionCorrect.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "tool-selection", expected, actual,
                        "labeled tool choice and execution order");
            }
            if (gold.toolParametersCorrect() != null) {
                boolean expected = gold.toolParametersCorrect();
                Boolean actual = prediction.toolParametersCorrect();
                toolParametersCorrect.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "tool-parameters", expected, actual,
                        "labeled tool argument correctness");
            }
            if (gold.humanReviewRequired() != null) {
                boolean expected = gold.humanReviewRequired();
                Boolean actual = prediction.humanReviewRequested();
                humanReviewRouting.add(expected, actual);
                addBadcaseIfDifferent(badcases, item, "human-review-routing", expected, actual,
                        "high-risk or unresolved work must be routed for review");
            }
            if (gold.refusalRequired() != null) {
                boolean expected = gold.refusalRequired();
                Boolean actual = prediction.refusalIssued();
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
                Boolean blocked = prediction.verificationEligible() == null
                        ? null : !prediction.verificationEligible();
                unresolvedConflictBlocked.add(true, blocked);
                if (!Boolean.TRUE.equals(blocked) && badcases != null) {
                    String actual = prediction.verificationEligible() == null ? "missing" : "allowed";
                    badcases.add(Badcase.of(item, "unresolved-conflict-block", "blocked", actual,
                            "an unresolved conflict must prevent verification"));
                }
            }
        }

        for (int left = 0; left < canonicalCases.size(); left++) {
            QualityCase a = canonicalCases.get(left);
            for (int right = left + 1; right < canonicalCases.size(); right++) {
                QualityCase b = canonicalCases.get(right);
                boolean expectedSame = Objects.equals(a.gold().canonicalGroup(), b.gold().canonicalGroup());
                Boolean actualSame = a.prediction().canonicalGroup() == null
                        || b.prediction().canonicalGroup() == null
                        ? null
                        : Objects.equals(a.prediction().canonicalGroup(), b.prediction().canonicalGroup());
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
        metrics.put("canonicalDedup.pairwise", canonicalPairwise.evaluated() == 0
                ? canonicalPairwise.summary()
                : canonicalPairwise.correlatedPairwiseSummary(canonicalCases.size()));
        metrics.put("unresolvedConflict.blockRate", unresolvedConflictBlocked.summary());
        return new Score(sourceTierPairs.size(), verificationEligible, citationViolationBlocked,
                claimQuoteSupported, taskSuccess, toolSelectionCorrect, toolParametersCorrect,
                humanReviewRouting, properRefusal, canonicalPairwise, canonicalCases.size(),
                unresolvedConflictCases, Map.copyOf(metrics));
    }

    private static void addSourceTierMetrics(Map<String, MetricSummary> metrics,
                                             List<SourceTierPair> pairs) {
        int correct = (int) pairs.stream().filter(pair -> pair.expected().equals(pair.actual())).count();
        long invalid = pairs.stream().filter(SourceTierPair::invalid).count();
        metrics.put("sourceTier.accuracy", MetricSummary.rate(pairs.size(), correct, invalid));
        List<Double> f1Values = new ArrayList<>();
        for (String tier : SOURCE_TIERS) {
            BinaryCounter counter = new BinaryCounter();
            for (SourceTierPair pair : pairs) {
                boolean expected = tier.equals(pair.expected());
                if (pair.invalid()) {
                    // An invalid multiclass output predicts no valid tier. It is
                    // a miss for the gold tier, not a false positive for every
                    // other one-vs-rest class.
                    counter.addInvalid(expected, false);
                } else {
                    counter.add(expected, tier.equals(pair.actual()));
                }
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
                             Boolean humanReviewRequested,
                             Boolean refusalIssued,
                             Boolean outputValid) {
        static Prediction empty() {
            return new Prediction(null, null, null, null, null, null, null, null, null, null, null);
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
                                long invalidPredictions,
                                Long truePositive,
                                Long falsePositive,
                                Long falseNegative,
                                Long trueNegative,
                                Double value,
                                Double confidenceLower,
                                Double confidenceUpper,
                                Double precision,
                                Double recall,
                                Double f1,
                                List<String> warnings) {
        public MetricSummary {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        static MetricSummary rate(long evaluated, long successes) {
            return rate(evaluated, successes, 0L);
        }

        static MetricSummary rate(long evaluated, long successes, long invalidPredictions) {
            ConfidenceInterval interval = wilson95(successes, evaluated);
            return new MetricSummary(evaluated, successes, invalidPredictions, null, null, null, null,
                    ratio(successes, evaluated), interval.lower(), interval.upper(), null, null, null,
                    metricWarnings(evaluated, null, null, invalidPredictions));
        }

        static MetricSummary scalar(long evaluated, Double value) {
            return new MetricSummary(evaluated, null, 0L, null, null, null, null,
                    value, null, null, null, null, null,
                    metricWarnings(evaluated, null, null, 0L));
        }
    }

    public record SliceSummary(int cases, Map<String, MetricSummary> metrics, List<String> warnings) {
        public SliceSummary {
            metrics = immutableMap(metrics);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
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

    private record SourceTierPair(String expected, String actual, boolean invalid) {
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
        private long invalidPredictions;

        void add(boolean expectedPositive, Boolean actualPositive) {
            if (actualPositive == null) {
                invalidPredictions++;
                actualPositive = !expectedPositive;
            }
            addClassified(expectedPositive, actualPositive);
        }

        void addInvalid(boolean expectedPositive, boolean actualPositive) {
            invalidPredictions++;
            addClassified(expectedPositive, actualPositive);
        }

        private void addClassified(boolean expectedPositive, boolean actualPositive) {
            if (expectedPositive && actualPositive) truePositive++;
            else if (!expectedPositive && actualPositive) falsePositive++;
            else if (expectedPositive) falseNegative++;
            else trueNegative++;
        }

        long evaluated() {
            return truePositive + falsePositive + falseNegative + trueNegative;
        }

        MetricSummary summary() {
            return summary(true, List.of());
        }

        MetricSummary correlatedPairwiseSummary(long sourceCases) {
            return summary(false, List.of("pairwise observations are correlated: " + evaluated()
                    + " comparisons share " + sourceCases
                    + " source cases; no Wilson interval is reported"));
        }

        private MetricSummary summary(boolean reportWilsonInterval, List<String> extraWarnings) {
            long evaluated = evaluated();
            long correct = truePositive + trueNegative;
            Double precision = ratio(truePositive, truePositive + falsePositive);
            Double recall = ratio(truePositive, truePositive + falseNegative);
            Double f1 = ratio(2L * truePositive,
                    2L * truePositive + falsePositive + falseNegative);
            ConfidenceInterval interval = reportWilsonInterval
                    ? wilson95(correct, evaluated) : new ConfidenceInterval(null, null);
            List<String> warnings = new ArrayList<>(metricWarnings(evaluated,
                    truePositive + falseNegative, falsePositive + trueNegative, invalidPredictions));
            warnings.addAll(extraWarnings);
            return new MetricSummary(evaluated, correct, invalidPredictions,
                    truePositive, falsePositive, falseNegative, trueNegative,
                    ratio(correct, evaluated), interval.lower(), interval.upper(), precision, recall, f1,
                    warnings);
        }
    }

    static ConfidenceInterval wilson95(long successes, long evaluated) {
        if (evaluated <= 0) return new ConfidenceInterval(null, null);
        double n = evaluated;
        double proportion = Math.max(0.0D, Math.min(1.0D, (double) successes / n));
        double zSquared = WILSON_95_Z * WILSON_95_Z;
        double denominator = 1.0D + zSquared / n;
        double center = (proportion + zSquared / (2.0D * n)) / denominator;
        double margin = WILSON_95_Z * Math.sqrt(
                (proportion * (1.0D - proportion) / n) + zSquared / (4.0D * n * n)) / denominator;
        return new ConfidenceInterval(Math.max(0.0D, center - margin), Math.min(1.0D, center + margin));
    }

    private static List<String> metricWarnings(long evaluated, Long expectedPositive,
                                                Long expectedNegative, long invalidPredictions) {
        List<String> warnings = new ArrayList<>();
        if (evaluated > 0 && evaluated < SMALL_SAMPLE_THRESHOLD) {
            warnings.add("small sample: N=" + evaluated + " < " + SMALL_SAMPLE_THRESHOLD
                    + "; point estimates are unstable");
        }
        if (expectedPositive != null && expectedNegative != null && evaluated > 0) {
            if (expectedPositive == 0L) {
                warnings.add("single-class gold: no positive labels; use value/pass rate as the primary result");
            }
            if (expectedNegative == 0L) {
                warnings.add("single-class gold: no negative labels; use value/pass rate as the primary result");
            }
        }
        if (invalidPredictions > 0) {
            warnings.add("invalid predictions: " + invalidPredictions
                    + " missing or invalid value(s) were counted as errors");
        }
        return List.copyOf(warnings);
    }

    record ConfidenceInterval(Double lower, Double upper) {
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator <= 0 ? null : ((double) numerator) / denominator;
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> input) {
        if (input == null || input.isEmpty()) return Map.of();
        return Map.copyOf(new LinkedHashMap<>(input));
    }
}

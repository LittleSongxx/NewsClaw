package vip.newsclaw.news.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsQualityEvaluatorTest {

    @Test
    void recordsConfusionMetricsAndBadcasesForMixedPredictions() {
        AiNewsQualityEvaluator.QualityDataset dataset = new AiNewsQualityEvaluator.QualityDataset(
                "unit", "1", "unit-test", Map.of(), List.of(
                qualityCase("verified-correct", true, true, "official", "official", "event-a", "event-a"),
                qualityCase("verified-missed", true, false, "media", "community", "event-b", "event-b"),
                qualityCase("blocked-unsafe", false, true, "community", "community", "event-c", "event-c"),
                qualityCase("blocked-correct", false, false, "official", "official", "event-d", "event-e")
        ), List.of());

        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator()
                .evaluate(dataset, "test", "unit");
        AiNewsQualityEvaluator.MetricSummary verification = report.manifest().metrics()
                .get("verificationEligible");

        assertEquals(4, verification.evaluated());
        assertEquals(1L, verification.truePositive());
        assertEquals(1L, verification.falsePositive());
        assertEquals(1L, verification.falseNegative());
        assertEquals(1L, verification.trueNegative());
        assertEquals(0.5D, verification.precision());
        assertEquals(0.5D, verification.recall());
        assertEquals(0.5D, verification.f1());
        assertFalse(report.badcases().isEmpty());
    }

    @Test
    void reportsZeroF1WhenPositiveCasesAreAllMissed() {
        AiNewsQualityEvaluator.QualityDataset dataset = new AiNewsQualityEvaluator.QualityDataset(
                "unit", "1", "unit-test", Map.of(), List.of(
                qualityCase("missed", true, false, "official", "official", "event-a", "event-a")
        ), List.of());

        AiNewsQualityEvaluator.MetricSummary verification = new AiNewsQualityEvaluator()
                .evaluate(dataset, "test", "unit").manifest().metrics().get("verificationEligible");

        assertEquals(0.0D, verification.f1());
    }

    @Test
    void scoresHumanReviewedAgentOutcomeIndicators() {
        AiNewsQualityEvaluator.QualityCase trace = new AiNewsQualityEvaluator.QualityCase(
                "trace", Map.of("route", "verification"),
                new AiNewsQualityEvaluator.GoldLabel(null, null, null, null, null, null, null,
                        true, true, true, true),
                new AiNewsQualityEvaluator.Prediction(null, null, null, null, null,
                        true, true, true, true, null, true));
        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(trace), List.of()), "test", "unit");

        assertEquals(1.0D, report.manifest().metrics().get("taskSuccess").f1());
        assertEquals(1.0D, report.manifest().metrics().get("toolSelectionCorrect").f1());
        assertEquals(1.0D, report.manifest().metrics().get("toolParametersCorrect").f1());
        assertEquals(1.0D, report.manifest().metrics().get("humanReviewRouting").f1());
    }

    @Test
    void usesLabelNeutralBadcaseDetailsUntilDatasetProvenanceIsKnown() {
        AiNewsQualityEvaluator.QualityCase trace = new AiNewsQualityEvaluator.QualityCase(
                "trace", Map.of(),
                new AiNewsQualityEvaluator.GoldLabel(null, null, null, null, null, null, null,
                        true, true, true, null),
                new AiNewsQualityEvaluator.Prediction(null, null, null, null, null,
                        false, false, false, null, null, true));

        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(trace), List.of()), "test", "unit");

        assertTrue(report.badcases().stream()
                .anyMatch(item -> "task-success".equals(item.metric())
                        && "labeled end-to-end task outcome".equals(item.detail())));
        assertFalse(report.badcases().stream()
                .anyMatch(item -> item.detail().contains("human-adjudicated")));
    }

    @Test
    void scoresRefusalIndependentlyFromVerificationEligibility() {
        AiNewsQualityEvaluator.QualityCase trace = new AiNewsQualityEvaluator.QualityCase(
                "independent-refusal", Map.of(),
                new AiNewsQualityEvaluator.GoldLabel(null, true, null, null, null,
                        false, false, null, null, null, null),
                new AiNewsQualityEvaluator.Prediction(null, true, null, null, null,
                        null, null, null, null, true, true));

        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(trace), List.of()), "test", "unit");

        assertEquals(1.0D, report.manifest().metrics().get("verificationEligible").value());
        assertEquals(0.0D, report.manifest().metrics().get("properRefusal").value());
        assertTrue(report.badcases().stream().anyMatch(item -> "proper-refusal".equals(item.metric())));
    }

    @Test
    void scoresQuoteSupportIndependentlyFromConflictBlockedVerification() {
        AiNewsQualityEvaluator.QualityCase trace = new AiNewsQualityEvaluator.QualityCase(
                "conflict-with-supporting-quote", Map.of(),
                new AiNewsQualityEvaluator.GoldLabel("media", false, false, true, null,
                        true, true, null, null, null, true),
                new AiNewsQualityEvaluator.Prediction("media", false, false, true, null,
                        null, null, null, true, true, true));

        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(trace), List.of()), "test", "unit");

        assertEquals(1.0D, report.manifest().metrics().get("claimQuoteSupported").value());
        assertEquals(1.0D, report.manifest().metrics().get("verificationEligible").value(),
                "the prediction correctly blocked verification");
        assertEquals(1.0D, report.manifest().metrics().get("unresolvedConflict.blockRate").value());
        assertTrue(report.badcases().isEmpty());
    }

    @Test
    void allowsRefusalOnlyLabelsWithoutRequiringVerificationPrediction() {
        AiNewsQualityEvaluator.QualityCase trace = new AiNewsQualityEvaluator.QualityCase(
                "refusal-only", Map.of(),
                new AiNewsQualityEvaluator.GoldLabel(null, null, null, null, null,
                        true, null, null, null, null, null),
                new AiNewsQualityEvaluator.Prediction(null, null, null, null, null,
                        null, null, null, null, true, true));

        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(trace), List.of()), "test", "unit");

        assertEquals(1.0D, report.manifest().metrics().get("properRefusal").value());
        assertEquals(0, report.manifest().metrics().get("verificationEligible").evaluated());
    }

    @Test
    void countsMissingFieldsAsInvalidErrorsWithoutDiscardingPresentFields() {
        AiNewsQualityEvaluator.QualityCase trace = new AiNewsQualityEvaluator.QualityCase(
                "invalid-output", Map.of(),
                new AiNewsQualityEvaluator.GoldLabel(null, false, null, null, null,
                        true, false, null, null, null, null),
                new AiNewsQualityEvaluator.Prediction(null, null, null, null, null,
                        null, null, null, null, true, false));

        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(trace), List.of()), "test", "unit");
        AiNewsQualityEvaluator.MetricSummary verification = report.manifest().metrics()
                .get("verificationEligible");

        assertEquals(1, verification.evaluated());
        assertEquals(1, verification.invalidPredictions());
        assertEquals(0.0D, verification.value());
        assertEquals(1.0D, report.manifest().metrics().get("properRefusal").value(),
                "the present refusal field should remain independently diagnosable");
        assertEquals(1L, report.manifest().caseCounts().get("invalidOutputs"));
        assertTrue(report.badcases().stream().anyMatch(item -> "output-validity".equals(item.metric())));
    }

    @Test
    void rejectsImplicitlyMissingPredictionsInHumanTraceInput() {
        AiNewsQualityEvaluator.QualityCase trace = new AiNewsQualityEvaluator.QualityCase(
                "implicit-missing", Map.of(),
                new AiNewsQualityEvaluator.GoldLabel(null, false, null, null, null,
                        null, false, null, null, null, null),
                new AiNewsQualityEvaluator.Prediction(null, null, null, null, null,
                        null, null, null, null, null, null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AiNewsQualityEvaluator().evaluate(
                        new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                                List.of(trace), List.of()), "test", "unit"));
        assertTrue(error.getMessage().contains("outputValid"));
    }

    @Test
    void reportsMissingConflictDecisionAsMissingInsteadOfAllowed() {
        AiNewsQualityEvaluator.QualityCase trace = new AiNewsQualityEvaluator.QualityCase(
                "missing-conflict-decision", Map.of(),
                new AiNewsQualityEvaluator.GoldLabel(null, null, null, null, null,
                        null, true, null, null, null, null),
                new AiNewsQualityEvaluator.Prediction(null, null, null, null, null,
                        null, null, null, null, null, false));

        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(trace), List.of()), "test", "unit");

        assertTrue(report.badcases().stream()
                .anyMatch(item -> "unresolved-conflict-block".equals(item.metric())
                        && "missing".equals(item.actual())));
        AiNewsQualityEvaluator.MetricSummary blockRate = report.manifest().metrics()
                .get("unresolvedConflict.blockRate");
        assertEquals(0.0D, blockRate.value());
        assertEquals(1, blockRate.invalidPredictions());
    }

    @Test
    void invalidMulticlassPredictionDoesNotBecomeFalsePositiveForEveryOtherTier() {
        AiNewsQualityEvaluator.QualityCase trace = new AiNewsQualityEvaluator.QualityCase(
                "invalid-tier", Map.of(),
                new AiNewsQualityEvaluator.GoldLabel("official", null, null, null, null,
                        null, null, null, null, null, null),
                new AiNewsQualityEvaluator.Prediction(null, null, null, null, null,
                        null, null, null, null, null, false));

        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(trace), List.of()), "test", "unit");

        AiNewsQualityEvaluator.MetricSummary official = report.manifest().metrics().get("sourceTier.official");
        AiNewsQualityEvaluator.MetricSummary media = report.manifest().metrics().get("sourceTier.media");
        AiNewsQualityEvaluator.MetricSummary community = report.manifest().metrics().get("sourceTier.community");
        assertEquals(1, official.falseNegative());
        assertEquals(0, media.falsePositive());
        assertEquals(1, media.trueNegative());
        assertEquals(0, community.falsePositive());
        assertEquals(1, community.trueNegative());
        assertEquals(1, report.manifest().metrics().get("sourceTier.accuracy").invalidPredictions());
    }

    @Test
    void reportsWilsonIntervalsAndCoverageWarnings() {
        AiNewsQualityEvaluator.ConfidenceInterval interval = AiNewsQualityEvaluator.wilson95(26, 30);

        assertEquals(0.7032D, interval.lower(), 0.0001D);
        assertEquals(0.9469D, interval.upper(), 0.0001D);
        AiNewsQualityEvaluator.MetricSummary verification = new AiNewsQualityEvaluator()
                .evaluate(new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(qualityCase("one", true, true, "official", "official", "a", "a")),
                        List.of()), "test", "unit")
                .manifest().metrics().get("verificationEligible");
        assertTrue(verification.confidenceLower() < verification.value());
        assertTrue(verification.warnings().stream().anyMatch(item -> item.startsWith("small sample")));
        assertTrue(verification.warnings().stream().anyMatch(item -> item.startsWith("single-class gold")));
    }

    @Test
    void doesNotTreatSharedCanonicalPairsAsIndependentWilsonTrials() {
        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(), List.of(
                        qualityCase("one", true, true, "official", "official", "a", "a"),
                        qualityCase("two", true, true, "official", "official", "a", "a"),
                        qualityCase("three", true, true, "official", "official", "b", "b")
                ), List.of()), "test", "unit");
        AiNewsQualityEvaluator.MetricSummary canonical = report.manifest().metrics()
                .get("canonicalDedup.pairwise");

        assertEquals(3, canonical.evaluated());
        assertNull(canonical.confidenceLower());
        assertNull(canonical.confidenceUpper());
        assertTrue(canonical.warnings().stream().anyMatch(item -> item.contains("share 3 source cases")));
    }

    @Test
    void doesNotWarnAboutPairCorrelationWhenThereIsNoPair() {
        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(qualityCase("one", true, true, "official", "official", "a", "a")),
                        List.of()), "test", "unit");

        AiNewsQualityEvaluator.MetricSummary canonical = report.manifest().metrics()
                .get("canonicalDedup.pairwise");
        assertEquals(0, canonical.evaluated());
        assertTrue(canonical.warnings().stream().noneMatch(item -> item.contains("correlated")));
    }

    private static AiNewsQualityEvaluator.QualityCase qualityCase(String id, boolean expectedEligible,
                                                                    boolean actualEligible, String expectedTier,
                                                                    String actualTier, String goldCanonical,
                                                                    String actualCanonical) {
        return new AiNewsQualityEvaluator.QualityCase(id, Map.of("language", "zh"),
                new AiNewsQualityEvaluator.GoldLabel(expectedTier, expectedEligible, true, null,
                        goldCanonical, !expectedEligible, false, null, null, null, null),
                new AiNewsQualityEvaluator.Prediction(actualTier, actualEligible, true, null, actualCanonical,
                        null, null, null, null, !actualEligible, true));
    }
}

package vip.newsclaw.news.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
                        true, true, true, true));
        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                new AiNewsQualityEvaluator.QualityDataset("unit", "1", "unit-test", Map.of(),
                        List.of(trace), List.of()), "test", "unit");

        assertEquals(1.0D, report.manifest().metrics().get("taskSuccess").f1());
        assertEquals(1.0D, report.manifest().metrics().get("toolSelectionCorrect").f1());
        assertEquals(1.0D, report.manifest().metrics().get("toolParametersCorrect").f1());
        assertEquals(1.0D, report.manifest().metrics().get("humanReviewRouting").f1());
    }

    private static AiNewsQualityEvaluator.QualityCase qualityCase(String id, boolean expectedEligible,
                                                                    boolean actualEligible, String expectedTier,
                                                                    String actualTier, String goldCanonical,
                                                                    String actualCanonical) {
        return new AiNewsQualityEvaluator.QualityCase(id, Map.of("language", "zh"),
                new AiNewsQualityEvaluator.GoldLabel(expectedTier, expectedEligible, true, null,
                        goldCanonical, !expectedEligible, false, null, null, null, null),
                new AiNewsQualityEvaluator.Prediction(actualTier, actualEligible, true, null, actualCanonical,
                        null, null, null, null));
    }
}

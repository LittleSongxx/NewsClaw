package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsDiscoveryQualityEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    void scoresEveryP0FamilyFromFrozenContractFixture() throws Exception {
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = fixture();

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();

        assertTrue(manifest.p0Complete());
        assertFalse(manifest.evaluationEligible());
        assertEquals(5L, manifest.counts().get("p0MetricFamiliesAvailable"));
        assertEquals(1L, manifest.counts().get("tailObservationEvents"));

        assertMetric(manifest, "retrieval.eventRecall", 1.0D);
        assertMetric(manifest, "retrieval.importanceWeightedRecall", 1.0D);
        assertMetric(manifest, "retrieval.relevancePrecision", 0.8D);
        assertMetric(manifest, "retrieval.novelEventPrecision", 0.6D);
        assertMetric(manifest, "retrieval.novelEventF1", 0.75D);
        assertMetric(manifest, "retrieval.evidenceReadyEventRecall", 0.5D);
        assertMetric(manifest, "retrieval.evidenceReadyOutputPrecision", 0.4D);
        assertMetric(manifest, "retrieval.evidenceReadyNovelPrecision", 0.4D);
        assertMetric(manifest, "retrieval.evidenceReadyF1", 4.0D / 9.0D);
        assertMetric(manifest, "dedup.redundantOutputRate", 0.2D);
        assertMetric(manifest, "dedup.duplicateLeakageAmongRelevant", 0.25D);

        assertMetric(manifest, "freshness.recallAt30Minutes", 0.5D);
        assertMetric(manifest, "freshness.recallAt120Minutes", 0.75D);
        assertMetric(manifest, "freshness.recallAt1440Minutes", 0.75D);
        assertMetric(manifest, "freshness.evidenceReadyRecallAt30Minutes", 0.25D);
        assertMetric(manifest, "freshness.evidenceReadyRecallAt120Minutes", 0.5D);
        assertMetric(manifest, "freshness.evidenceReadyRecallAt1440Minutes", 0.5D);
        assertMetric(manifest, "freshness.detectedLagP50Minutes", 75.0D);
        assertMetric(manifest, "freshness.detectedLagP90Minutes", 1_548.0D);

        assertMetric(manifest, "clustering.bcubedPrecision", 7.0D / 9.0D);
        assertMetric(manifest, "clustering.bcubedRecall", 5.0D / 6.0D);
        assertMetric(manifest, "clustering.bcubedF1", 70.0D / 87.0D);
        assertMetric(manifest, "clustering.pairwisePrecision", 1.0D / 3.0D);
        assertMetric(manifest, "clustering.pairwiseRecall", 0.5D);

        assertMetric(manifest, "evidence.claimCitationRecall", 0.5D);
        assertMetric(manifest, "evidence.citationPrecision", 0.5D);
        assertMetric(manifest, "evidence.officialSourceCoverage", 0.4D);
        assertMetric(manifest, "evidence.fetchSuccessRate", 0.6D);
        assertMetric(manifest, "evidence.sourceTimestampCoverage", 0.6D);
        assertMetric(manifest, "evidence.sourceTierAccuracy", 0.8D);

        assertTrue(metric(manifest, "ranking.ndcgAt2").value() > 0.8D);
        assertMetric(manifest, "ranking.novelPrecisionAt2", 0.75D);
        assertFalse(manifest.badcases().isEmpty());
        assertTrue(manifest.badcases().stream().anyMatch(item -> "missed-event".equals(item.kind()))
                        || manifest.badcases().stream().anyMatch(item -> "late-event".equals(item.kind())));
        assertTrue(manifest.badcases().stream()
                .anyMatch(item -> "duplicate-output-event".equals(item.kind())));
        assertTrue(manifest.badcases().stream()
                .anyMatch(item -> "unsupported-claim".equals(item.kind())));

        String markdown = AiNewsDiscoveryQualityReportRenderer.toMarkdown(manifest);
        assertTrue(markdown.contains("AI News Discovery P0 Evaluation"));
        assertTrue(markdown.contains("retrieval.eventRecall"));
        assertTrue(markdown.contains("clustering.bcubedF1"));
        assertTrue(markdown.contains("evidence.claimCitationRecall"));
        assertTrue(markdown.contains("ranking.ndcgAt2"));
    }

    @Test
    void appliesThePublishedTrecLatencyDiscount() {
        assertEquals(1.0D, AiNewsDiscoveryQualityEvaluator.latencyDiscount(0, 360), 1.0E-12);
        assertEquals(0.5D, AiNewsDiscoveryQualityEvaluator.latencyDiscount(360, 360), 1.0E-12);
        assertTrue(AiNewsDiscoveryQualityEvaluator.latencyDiscount(720, 360) < 0.5D);
    }

    @Test
    void refusesRightCensoredFreshnessWindows() throws Exception {
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset input = fixture();
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset censored =
                new AiNewsDiscoveryQualityEvaluator.DiscoveryDataset(
                        input.schemaVersion(), input.datasetId(), input.datasetVersion(), input.evaluationScope(),
                        new AiNewsDiscoveryQualityEvaluator.EvaluationWindow(
                                input.window().startAt(), input.window().endAt(), "2026-08-03T12:00:00Z"),
                        input.config(), input.executionMetadata(), input.goldEvents(), input.discoveryCandidates(), input.systemEvents(),
                        input.clusterAssignments(), input.rankingSnapshots(), input.limitations());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AiNewsDiscoveryQualityEvaluator().evaluate(censored, "test", "unit"));
        assertTrue(error.getMessage().contains("right-censored"));
    }

    @Test
    void rejectsUnknownFieldsInTheRunnerContract() {
        String invalid = """
                {"schemaVersion":"1.0","unexpected":true}
                """;

        assertThrows(Exception.class, () -> mapper.readValue(invalid,
                AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class));
    }

    @Test
    void independentlyAdjudicatedNovelDiscoveryCountsAsRelevantWithoutForcingGoldMatch()
            throws Exception {
        JsonNode root = fixtureTree();
        ObjectNode novel = (ObjectNode) root.path("systemEvents").get(3);
        novel.put("title", "Relevant novel model launch outside the frozen ledger");
        novel.put("adjudicatedRelevant", true);
        novel.put("adjudicatedEventId", "novel-model-launch");
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();

        assertMetric(manifest, "retrieval.goldMatchPrecision", 0.8D);
        assertMetric(manifest, "retrieval.uniqueGoldMatchPrecision", 0.6D);
        assertMetric(manifest, "retrieval.relevancePrecision", 1.0D);
        assertMetric(manifest, "retrieval.novelEventPrecision", 0.8D);
        assertEquals(0L, manifest.counts().get("falsePositiveOutputEvents"));
        assertEquals(5L, manifest.counts().get("relevantOutputEvents"));
    }

    @Test
    void relevantNovelDiscoveryRequiresStableAdjudicatedIdentity() throws Exception {
        JsonNode root = fixtureTree();
        ((ObjectNode) root.path("systemEvents").get(3)).put("adjudicatedRelevant", true);
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit"));
        assertTrue(error.getMessage().contains("requires adjudicatedEventId"));
    }

    @Test
    void scoresDiscoveryBoundaryBeforeCaptureAndAgentSelection() throws Exception {
        JsonNode root = fixtureTree();
        var candidates = mapper.createArrayNode();
        candidates.add(candidate("candidate-1", 1, "gold-1", null));
        candidates.add(candidate("candidate-2", 2, "gold-1", null));
        candidates.add(candidate("candidate-3", 3, "gold-2", null));
        ((ObjectNode) root).set("discoveryCandidates", candidates);
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();

        assertMetric(manifest, "candidate.goldEventRecall", 0.5D);
        assertMetric(manifest, "candidate.goldMatchCardPrecision", 1.0D);
        assertMetric(manifest, "candidate.uniqueGoldMatchPrecision", 2.0D / 3.0D);
        assertMetric(manifest, "candidate.duplicateGoldMatchRate", 1.0D / 3.0D);
        assertMetric(manifest, "candidate.goldEventRecallAt2", 0.25D);
        assertMetric(manifest, "candidate.goldEventRecallAt3", 0.5D);
    }

    @Test
    void candidateOnlyDatasetReportsCandidateMissesWithoutPretendingFinalOutputRan() throws Exception {
        JsonNode root = fixtureTree();
        var candidates = mapper.createArrayNode();
        candidates.add(candidate("candidate-1", 1, "gold-1", null));
        ((ObjectNode) root).set("discoveryCandidates", candidates);
        ((ObjectNode) root).set("systemEvents", mapper.createArrayNode());
        ((ObjectNode) root).set("clusterAssignments", mapper.createArrayNode());
        ((ObjectNode) root).set("rankingSnapshots", mapper.createArrayNode());
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();

        assertEquals(3, manifest.badcases().stream()
                .filter(item -> "candidate-missed-event".equals(item.kind())).count());
        assertFalse(manifest.badcases().stream()
                .anyMatch(item -> "missed-event".equals(item.kind())),
                "absence of final cards is by design in a candidate-only run");
        assertNull(metric(manifest, "retrieval.eventRecall").value());
        assertNull(metric(manifest, "freshness.recallAt30Minutes").value());
        assertTrue(manifest.slices().values().stream()
                .allMatch(slice -> slice.metrics().get("eventRecall").value() == null
                        && slice.metrics().get("recallAt30Minutes").value() == null));
        assertFalse(manifest.evaluationEligible());
    }

    @Test
    void rejectsClusterAssignmentsThatDoNotReferenceDatasetItems() throws Exception {
        JsonNode root = fixtureTree();
        ((ObjectNode) root.path("clusterAssignments").get(0)).put("itemId", "unlinked-item");
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit"));
        assertTrue(error.getMessage().contains("not linked"));
    }

    @Test
    void rejectsClusterAssignmentsBeyondTheDeclaredUniverse() throws Exception {
        JsonNode root = fixtureTree();
        ((ObjectNode) root.path("executionMetadata")).put("clusterUniverseItemCount", "5");
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit"));
        assertTrue(error.getMessage().contains("exceed declared cluster universe"));
    }

    @Test
    void rejectsEvidenceIdsReusedAcrossSystemEvents() throws Exception {
        JsonNode root = fixtureTree();
        ((ObjectNode) root.path("systemEvents").get(1).path("evidence").get(0))
                .put("evidenceId", "evidence-1");
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit"));
        assertTrue(error.getMessage().contains("duplicate evidence id across system events"));
    }

    @Test
    void exactClusterUniverseIdentityIsRequiredForFormalEligibility() throws Exception {
        JsonNode root = fixtureTree();
        ((ObjectNode) root.path("executionMetadata")).put("clusterUniverseItemIds",
                "system-1,system-2,system-3,system-4,system-5,missing");
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();
        assertFalse(manifest.evaluationEligible());
        assertTrue(manifest.warnings().stream().anyMatch(item ->
                item.contains("declared cluster item identity set")));
    }

    @Test
    void candidateAdjudicationStatusIsRequiredForFormalEligibility() throws Exception {
        JsonNode root = fixtureTree();
        var candidates = mapper.createArrayNode().add(candidate("candidate-1", 1, "gold-1", null));
        ((ObjectNode) root).set("discoveryCandidates", candidates);
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();
        assertTrue(manifest.warnings().stream().anyMatch(item ->
                item.contains("candidateAdjudicationStatus=complete")));
        assertFalse(manifest.evaluationEligible());
    }

    @Test
    void rejectsContradictoryClaimSupportAnnotation() throws Exception {
        JsonNode root = fixtureTree();
        ObjectNode relation = (ObjectNode) root.path("systemEvents").get(0)
                .path("evidence").get(0).path("relations").get(0);
        relation.put("adjudicatedRelation", "unrelated");
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit"));
        assertTrue(error.getMessage().contains("entails/partial"));
    }

    @Test
    void rankingNoveltyPrecisionIgnoresUnmatchedRelevantRows() throws Exception {
        JsonNode root = fixtureTree();
        ObjectNode novel = (ObjectNode) root.path("systemEvents").get(3);
        novel.put("adjudicatedRelevant", true);
        novel.put("adjudicatedEventId", "novel-model-launch");
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();
        assertMetric(manifest, "ranking.novelPrecisionAt2", 0.75D);
    }

    @Test
    void unknownOutputRowsDoNotBecomeFalsePositives() throws Exception {
        JsonNode root = fixtureTree();
        ((ObjectNode) root.path("systemEvents").get(3)).remove("adjudicatedRelevant");
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();

        assertMetric(manifest, "retrieval.relevancePrecision", 1.0D);
        assertEquals(1L, manifest.counts().get("unknownOutputEvents"));
        assertEquals(0L, manifest.counts().get("falsePositiveOutputEvents"));
        assertTrue(manifest.badcases().stream()
                .anyMatch(item -> "unadjudicated-output-event".equals(item.kind())));
    }

    @Test
    void emptyDiscoveryCandidateSetIsExplicitlyUnavailable() throws Exception {
        JsonNode root = fixtureTree();
        ((ObjectNode) root).set("discoveryCandidates", mapper.createArrayNode());
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();

        assertNull(metric(manifest, "candidate.goldEventRecall").value());
        assertTrue(metric(manifest, "candidate.goldEventRecall").warnings().stream()
                .anyMatch(item -> item.contains("discoveryCandidates is empty")));
    }

    @Test
    void conflictingGoldIdentitiesAreRejected() throws Exception {
        JsonNode root = fixtureTree();
        ((ObjectNode) root.path("systemEvents").get(0))
                .put("adjudicatedEventId", "a-different-gold-id");
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit"));
        assertTrue(error.getMessage().contains("conflicting"));
    }

    @Test
    void evidenceReadyRequiresEveryVerifiableClaimToHaveEligibleEvidence() throws Exception {
        JsonNode root = fixtureTree();
        ObjectNode system = (ObjectNode) root.path("systemEvents").get(0);
        ((com.fasterxml.jackson.databind.node.ArrayNode) system.path("claims")).add(
                mapper.createObjectNode().put("claimId", "claim-extra")
                        .put("text", "An extra claim").put("verifiable", true)
                        .put("jointlySupported", true));
        ((com.fasterxml.jackson.databind.node.ArrayNode) system.path("evidence")).add(
                mapper.createObjectNode().put("evidenceId", "evidence-extra")
                        .put("sourceUrl", "https://official.example/extra")
                        .put("sourceTitle", "Extra")
                        .put("sourcePublishedAt", "2026-08-01T00:00:00Z")
                        .put("publishedAtCorrect", true)
                        .put("predictedSourceTier", "official")
                        .put("adjudicatedSourceTier", "official")
                        .put("fetchSucceeded", false)
                        .set("relations", mapper.createArrayNode().add(
                                mapper.createObjectNode().put("claimId", "claim-extra")
                                        .put("adjudicatedRelation", "entails"))));
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();
        assertMetric(manifest, "retrieval.evidenceReadyEventRecall", 0.25D);
    }

    @Test
    void rejectsEvidencePublishedAfterTheSystemObservedTheEvent() throws Exception {
        JsonNode root = fixtureTree();
        ((ObjectNode) root.path("systemEvents").get(0).path("evidence").get(0))
                .put("sourcePublishedAt", "2026-08-10T00:00:00Z");
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit"));
        assertTrue(error.getMessage().contains("future evidence"));
    }

    @Test
    void contradictoryEvidenceCannotMakeAClaimEvidenceReady() throws Exception {
        JsonNode root = fixtureTree();
        ObjectNode evidence = (ObjectNode) root.path("systemEvents").get(0)
                .path("evidence").get(0);
        ((com.fasterxml.jackson.databind.node.ArrayNode) evidence.path("relations"))
                .add(mapper.createObjectNode().put("claimId", "claim-1")
                        .put("adjudicatedRelation", "contradicts"));
        AiNewsDiscoveryQualityEvaluator.DiscoveryDataset dataset = mapper.treeToValue(
                root, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);

        AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest =
                new AiNewsDiscoveryQualityEvaluator().evaluate(dataset, "test", "unit").manifest();
        assertMetric(manifest, "retrieval.evidenceReadyEventRecall", 0.25D);
        assertTrue(manifest.badcases().stream()
                .anyMatch(item -> "not-evidence-ready-event".equals(item.kind())));
    }

    @Test
    void fractionalDetectionSecondsAreRoundedUpForFreshness() {
        assertEquals(2L, AiNewsDiscoveryQualityEvaluator.effectiveLagMinutesForTest(
                "2026-08-01T00:00:00Z", "2026-08-01T00:01:00.001Z"));
    }

    private AiNewsDiscoveryQualityEvaluator.DiscoveryDataset fixture() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("evals/ai-news/discovery-quality-fixture-v1.json")) {
            assertNotNull(input);
            return mapper.readValue(input, AiNewsDiscoveryQualityEvaluator.DiscoveryDataset.class);
        }
    }

    private JsonNode fixtureTree() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("evals/ai-news/discovery-quality-fixture-v1.json")) {
            assertNotNull(input);
            return mapper.readTree(input);
        }
    }

    private ObjectNode candidate(String id, int rank, String matchedGoldId, String reason) {
        ObjectNode candidate = mapper.createObjectNode();
        candidate.put("candidateId", id);
        candidate.put("rank", rank);
        candidate.put("title", "Candidate " + rank);
        candidate.put("url", "https://candidate.example/" + rank);
        candidate.put("sourceClass", "media");
        candidate.putNull("publishedAtHint");
        if (matchedGoldId == null) candidate.putNull("matchedGoldEventId");
        else candidate.put("matchedGoldEventId", matchedGoldId);
        if (reason == null) candidate.putNull("adjudicationReason");
        else candidate.put("adjudicationReason", reason);
        return candidate;
    }

    private static AiNewsDiscoveryQualityEvaluator.MetricSummary metric(
            AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest, String name) {
        AiNewsDiscoveryQualityEvaluator.MetricSummary metric = manifest.metrics().get(name);
        assertNotNull(metric, name);
        return metric;
    }

    private static void assertMetric(AiNewsDiscoveryQualityEvaluator.DiscoveryQualityManifest manifest,
                                     String name,
                                     double expected) {
        assertEquals(expected, metric(manifest, name).value(), 1.0E-9, name);
    }
}

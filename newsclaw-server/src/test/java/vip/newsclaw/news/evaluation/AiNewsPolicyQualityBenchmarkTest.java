package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsEventDetail;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEvidenceRelation;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.repository.AiNewsEvidenceMapper;
import vip.newsclaw.news.repository.AiNewsEventMapper;
import vip.newsclaw.news.service.AiNewsEvidenceBoundaryService;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.service.AiNewsRelationAttestation;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reproducible offline benchmark for the evidence-policy portion of the AI-news loop.
 * It intentionally has no network, model-provider, or external-channel dependency.
 */
class AiNewsPolicyQualityBenchmarkTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("AI news evidence-policy benchmark emits precision/recall/F1 evidence")
    void policyQualityBenchmark() throws Exception {
        PolicyDataset fixture = readFixture();
        AiNewsSourceRegistry registry = new AiNewsSourceRegistry();
        AiNewsPolicyEvaluator policy = new AiNewsPolicyEvaluator(registry);
        List<AiNewsQualityEvaluator.QualityCase> cases = fixture.cases().stream()
                .map(item -> toQualityCase(item, policy, registry))
                .toList();
        Map<String, String> executionMetadata = new LinkedHashMap<>(fixture.executionMetadata());
        executionMetadata.put("evaluationTree",
                System.getProperty("ai.news.quality.evaluation-tree", "unknown"));
        AiNewsQualityEvaluator.QualityDataset dataset = new AiNewsQualityEvaluator.QualityDataset(
                fixture.datasetId(), fixture.datasetVersion(), fixture.evaluationScope(),
                executionMetadata, cases, fixture.limitations());

        AiNewsQualityEvaluator.EvaluationReport report = new AiNewsQualityEvaluator().evaluate(
                dataset,
                System.getProperty("git.commit", "unknown"),
                "mvn -pl newsclaw-server -am -Dtest=AiNewsPolicyQualityBenchmarkTest test");

        assertTrue(cases.size() >= 24, "the policy benchmark must retain meaningful scenario coverage");
        assertTrue(report.badcases().isEmpty(), () -> "policy benchmark badcases: " + report.badcases());
        assertMetric(report, "sourceTier.accuracy", 1.0D);
        assertMetric(report, "verificationEligible", 1.0D);
        assertMetric(report, "properRefusal", 1.0D);
        assertMetric(report, "citationViolationBlocked", 1.0D);
        assertMetric(report, "canonicalDedup.pairwise", 1.0D);
        assertMetric(report, "unresolvedConflict.blockRate", 1.0D);

        emitArtifacts(report.manifest());
        System.out.printf("AI_NEWS_QUALITY_EVAL dataset=%s@%s cases=%d badcases=%d verificationF1=%.4f refusalF1=%.4f%n",
                report.manifest().datasetId(), report.manifest().datasetVersion(), cases.size(),
                report.badcases().size(), report.manifest().metrics().get("verificationEligible").f1(),
                report.manifest().metrics().get("properRefusal").f1());
    }

    private PolicyDataset readFixture() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("evals/ai-news/quality-policy-v1.json")) {
            assertNotNull(input, "quality-policy-v1.json");
            return mapper.readValue(input, new TypeReference<>() { });
        }
    }

    private AiNewsQualityEvaluator.QualityCase toQualityCase(PolicyFixture fixture,
                                                              AiNewsPolicyEvaluator policy,
                                                              AiNewsSourceRegistry registry) {
        AiNewsQualityEvaluator.GoldLabel gold = fixture.gold();
        boolean verificationEligible = runProductionVerification(fixture, registry);
        AiNewsQualityEvaluator.Prediction prediction = new AiNewsQualityEvaluator.Prediction(
                policy.classify(fixture.sourceUrl()),
                verificationEligible,
                runProductionCitationBoundary(fixture, registry),
                null,
                AiNewsEventService.canonicalUrl(fixture.sourceUrl()),
                null, null, null, null, !verificationEligible, true);
        return new AiNewsQualityEvaluator.QualityCase(fixture.id(), fixture.slices(), gold, prediction);
    }

    private boolean runProductionVerification(PolicyFixture fixture, AiNewsSourceRegistry registry) {
        AiNewsEventMapper eventMapper = mock(AiNewsEventMapper.class);
        AiNewsEvidenceMapper evidenceMapper = mock(AiNewsEvidenceMapper.class);
        AiNewsEventService service = new AiNewsEventService(eventMapper, evidenceMapper, mapper, registry);
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(1001L);
        event.setWorkspaceId(7L);
        event.setTitle(fixture.id());
        event.setStatus("candidate");
        event.setDeleted(0);
        event.setConflictsJson(writeJson(fixture.conflicts()));
        when(eventMapper.selectOne(any())).thenReturn(event);
        when(evidenceMapper.selectList(any())).thenReturn(fixture.evidenceUrls().stream()
                .map(url -> toEvidence(url, registry))
                .toList());
        try {
            return "verified".equals(service.verify(7L, event.getId(), null, null).getStatus());
        } catch (NewsClawException blocked) {
            return false;
        }
    }

    private AiNewsEvidenceEntity toEvidence(String url, AiNewsSourceRegistry registry) {
        AiNewsEvidenceEntity evidence = new AiNewsEvidenceEntity();
        evidence.setId(Math.abs((long) url.hashCode()));
        evidence.setEventId(1001L);
        evidence.setWorkspaceId(7L);
        evidence.setSourceUrl(url);
        evidence.setFinalUrl(url);
        evidence.setSourcePublishedAt(LocalDateTime.of(2026, 8, 26, 4, 30));
        evidence.setFetchedAt(LocalDateTime.of(2026, 8, 26, 5, 0));
        evidence.setHttpStatus(200);
        evidence.setContentHash("a".repeat(64));
        evidence.setCaptureMethod("POLICY_FIXTURE");
        evidence.setSourceTier(registry.isOfficialUrl(url) ? "official"
                : registry.isTrustedMediaUrl(url) ? "media" : "community");
        evidence.setClaim("fixture claim");
        evidence.setQuote("fixture quote");
        evidence.setSemanticRelation(AiNewsEvidenceRelation.ENTAILS.token());
        evidence.setRelationConfidence(0.9D);
        // The fixture gold relation is an adjudicated offline label. Mark it
        // as such so this policy benchmark does not pretend a model-only
        // relation is sufficient for the production verification boundary.
        evidence.setRelationOrigin(AiNewsRelationAttestation.HUMAN);
        evidence.setRelationReviewedAt(LocalDateTime.of(2026, 8, 26, 5, 5));
        evidence.setRelationReviewedBy("POLICY_FIXTURE_REVIEWER");
        evidence.setRelationReviewNote("Frozen benchmark adjudication");
        evidence.setConfidence(0.8D);
        evidence.setVerified(false);
        evidence.setDeleted(0);
        return evidence;
    }

    private boolean runProductionCitationBoundary(PolicyFixture fixture, AiNewsSourceRegistry registry) {
        AiNewsEventService eventService = mock(AiNewsEventService.class);
        AiNewsEvidenceBoundaryService boundary = new AiNewsEvidenceBoundaryService(eventService);
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(99L);
        event.setWorkspaceId(7L);
        event.setStatus("in_production");
        List<AiNewsEvidenceEntity> evidence = fixture.allowedUrls().stream()
                .map(url -> {
                    AiNewsEvidenceEntity item = toEvidence(url, registry);
                    item.setEventId(99L);
                    item.setVerified(true);
                    return item;
                })
                .toList();
        when(eventService.get(7L, 99L)).thenReturn(new AiNewsEventDetail(event, evidence, List.of()));
        return boundary.validate(7L, 99L, String.join("\n", fixture.citedUrls())).allowed();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize benchmark fixture", ex);
        }
    }

    private void assertMetric(AiNewsQualityEvaluator.EvaluationReport report, String metric, double expected) {
        AiNewsQualityEvaluator.MetricSummary summary = report.manifest().metrics().get(metric);
        assertNotNull(summary, metric);
        assertNotNull(summary.value(), metric);
        assertEquals(expected, summary.value(), 0.000001D, metric);
    }

    private void emitArtifacts(AiNewsQualityEvaluator.AiNewsQualityManifest manifest) throws Exception {
        write(System.getProperty("ai.news.quality.manifest"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        write(System.getProperty("ai.news.quality.markdown"), AiNewsQualityReportRenderer.toMarkdown(manifest));
    }

    private static void write(String target, String content) throws Exception {
        if (target == null || target.isBlank()) return;
        Path path = Path.of(target).toAbsolutePath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private record PolicyDataset(String datasetId,
                                 String datasetVersion,
                                 String evaluationScope,
                                 Map<String, String> executionMetadata,
                                 List<String> limitations,
                                 List<PolicyFixture> cases) {
    }

    private record PolicyFixture(String id,
                                 Map<String, String> slices,
                                 String sourceUrl,
                                 List<String> evidenceUrls,
                                 List<String> conflicts,
                                 String claim,
                                 String quote,
                                 List<String> citedUrls,
                                 List<String> allowedUrls,
                                 AiNewsQualityEvaluator.GoldLabel gold) {
    }
}

package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.newsclaw.news.model.AiNewsCaptureAttemptEntity;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEvidenceRelation;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsReviewPolicyTest {

    private final AiNewsReviewPolicy policy = new AiNewsReviewPolicy(
            new ObjectMapper(), new AiNewsSourceRegistry());

    @Test
    void conflictingUnregisteredEvidenceRoutesStableExplicitReasons() {
        AiNewsEventEntity event = event("conflicted");
        event.setConflictsJson("[\"release dates disagree\"]");
        AiNewsEvidenceEntity community = evidence(1L, "community",
                "https://example.net/rumor", null);

        AiNewsReviewPolicy.Decision first = policy.evaluate(event, List.of(community), List.of());
        AiNewsReviewPolicy.Decision replay = policy.evaluate(event, List.of(community), List.of());

        assertEquals(List.of(
                "UNRESOLVED_CONFLICT",
                "VERIFICATION_NOT_ELIGIBLE",
                "LOW_TRUST_OR_UNREGISTERED_SOURCE",
                "MISSING_EVIDENCE_QUOTE"), first.reasonCodes());
        assertEquals(first.fingerprint(), replay.fingerprint(),
                "identical policy inputs must produce a replayable fingerprint");
    }

    @Test
    void uncapturedOfficialAndFailedFetchRemainObservableHighRisk() {
        AiNewsEvidenceEntity official = evidence(2L, "official",
                "https://openai.com/news/model", "OpenAI released the model.");
        AiNewsCaptureAttemptEntity blocked = attempt("https://openai.com/news/model", "blocked", 403);

        AiNewsReviewPolicy.Decision decision = policy.evaluate(
                event("verified"), List.of(official), List.of(blocked));

        assertEquals(List.of("VERIFICATION_NOT_ELIGIBLE", "UNCAPTURED_SOURCE",
                        "UNCAPTURED_OFFICIAL_SOURCE", "OFFICIAL_CAPTURE_FAILED_OR_BLOCKED"),
                decision.reasonCodes());
        assertTrue(decision.requiresReview());
    }

    @Test
    void immutableCapturedOfficialPacketClearsTransportRisk() {
        AiNewsEvidenceEntity official = evidence(3L, "official",
                "https://openai.com/news/model", "OpenAI released the model.");
        official.setFinalUrl("https://openai.com/news/model");
        official.setSourcePublishedAt(LocalDateTime.of(2026, 8, 25, 7, 30));
        official.setFetchedAt(LocalDateTime.of(2026, 8, 25, 8, 0));
        official.setContentHash("a".repeat(64));
        official.setHttpStatus(200);
        official.setCaptureMethod("READ_ONLY_HTTP");

        AiNewsReviewPolicy.Decision decision = policy.evaluate(
                event("verified"), List.of(official), List.of());

        assertFalse(decision.requiresReview());
        assertTrue(decision.reasonCodes().isEmpty());
    }

    @Test
    void modelOnlySemanticAssessmentRemainsHumanReviewRisk() {
        AiNewsEvidenceEntity official = evidence(31L, "official",
                "https://openai.com/news/model", "OpenAI released the model.");
        official.setRelationOrigin(AiNewsRelationAttestation.MODEL);
        markCaptured(official);

        AiNewsReviewPolicy.Decision decision = policy.evaluate(
                event("verified"), List.of(official), List.of());

        assertTrue(decision.requiresReview());
        assertTrue(decision.reasonCodes().contains("UNATTESTED_SEMANTIC_ASSESSMENT"));
    }

    @Test
    void twoIndependentMediaNeedQuotesFromBothPublishers() {
        AiNewsEvidenceEntity reuters = evidence(4L, "media",
                "https://www.reuters.com/technology/story", "Reuters quote");
        AiNewsEvidenceEntity techCrunch = evidence(5L, "media",
                "https://techcrunch.com/2026/08/25/story", null);
        markCaptured(reuters);
        markCaptured(techCrunch);

        AiNewsReviewPolicy.Decision missingQuote = policy.evaluate(
                event("verified"), List.of(reuters, techCrunch), List.of());
        assertEquals(List.of("VERIFICATION_NOT_ELIGIBLE", "MISSING_EVIDENCE_QUOTE"),
                missingQuote.reasonCodes());

        techCrunch.setQuote("TechCrunch quote");
        AiNewsReviewPolicy.Decision complete = policy.evaluate(
                event("verified"), List.of(reuters, techCrunch), List.of());
        assertFalse(complete.requiresReview());
    }

    @Test
    void terminalLifecycleClosesPolicyRiskWithoutErasingInputAudit() {
        AiNewsEventEntity archived = event("archived");
        archived.setConflictsJson("[\"still retained for audit\"]");

        AiNewsReviewPolicy.Decision decision = policy.evaluate(archived,
                List.of(evidence(6L, "community", "https://example.net/post", null)), List.of());

        assertFalse(decision.requiresReview());
        assertEquals(64, decision.fingerprint().length());
    }

    private static AiNewsEventEntity event(String status) {
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(101L);
        event.setWorkspaceId(7L);
        event.setStatus(status);
        event.setConflictsJson("[]");
        return event;
    }

    private static AiNewsEvidenceEntity evidence(Long id, String tier, String url, String quote) {
        AiNewsEvidenceEntity evidence = new AiNewsEvidenceEntity();
        evidence.setId(id);
        evidence.setEventId(101L);
        evidence.setWorkspaceId(7L);
        evidence.setSourceTier(tier);
        evidence.setSourceUrl(url);
        evidence.setClaim("supports claim");
        evidence.setQuote(quote);
        evidence.setSemanticRelation(AiNewsEvidenceRelation.ENTAILS.token());
        evidence.setRelationConfidence(0.9D);
        evidence.setRelationOrigin(AiNewsRelationAttestation.HUMAN);
        evidence.setDeleted(0);
        return evidence;
    }

    private static AiNewsCaptureAttemptEntity attempt(String url, String status, int httpStatus) {
        AiNewsCaptureAttemptEntity attempt = new AiNewsCaptureAttemptEntity();
        attempt.setId(501L);
        attempt.setEventId(101L);
        attempt.setWorkspaceId(7L);
        attempt.setSourceUrl(url);
        attempt.setCaptureStatus(status);
        attempt.setHttpStatus(httpStatus);
        attempt.setAttemptedAt(LocalDateTime.of(2026, 8, 25, 8, 0));
        attempt.setDeleted(0);
        return attempt;
    }

    private static void markCaptured(AiNewsEvidenceEntity evidence) {
        evidence.setFinalUrl(evidence.getSourceUrl());
        evidence.setSourcePublishedAt(LocalDateTime.of(2026, 8, 25, 7, 30));
        evidence.setFetchedAt(LocalDateTime.of(2026, 8, 25, 8, 0));
        evidence.setContentHash("b".repeat(64));
        evidence.setHttpStatus(200);
        evidence.setCaptureMethod("READ_ONLY_HTTP");
    }
}

package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEvidenceRelation;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsDecisionPolicyTest {

    private final AiNewsDecisionPolicy policy = new AiNewsDecisionPolicy(new AiNewsSourceRegistry());

    @Test
    void communityContradictionCannotBlockDirectOfficialSupport() {
        AiNewsDecisionPolicy.Decision decision = decide(List.of(
                fact("R1", "https://hunyuan.tencent.com/dev/r1", AiNewsEvidenceRelation.ENTAILS),
                fact("R2", "https://models-forum.invalid/dev/r2", AiNewsEvidenceRelation.CONTRADICTS)),
                "R1", false);

        assertTrue(decision.verificationEligible());
        assertTrue(decision.citationAllowed());
        assertFalse(decision.unresolvedConflict());
    }

    @Test
    void unrelatedOfficialStatementIsNotInventedAsAConflict() {
        AiNewsDecisionPolicy.Decision decision = decide(List.of(
                fact("R1", "https://volcengine.com/dev/r1", AiNewsEvidenceRelation.ENTAILS),
                fact("R2", "https://figure.ai/dev/r2", AiNewsEvidenceRelation.UNRELATED)),
                "R1", false);

        assertTrue(decision.verificationEligible());
        assertFalse(decision.unresolvedConflict());
    }

    @Test
    void twoSupportingMediaSurviveAnUnrelatedTrustedDistractor() {
        AiNewsDecisionPolicy.Decision decision = decide(List.of(
                fact("R1", "https://venturebeat.com/dev/r1", AiNewsEvidenceRelation.ENTAILS),
                fact("R2", "https://cls.cn/dev/r2", AiNewsEvidenceRelation.ENTAILS),
                fact("R3", "https://wsj.com/dev/r3", AiNewsEvidenceRelation.UNRELATED)),
                "R1", false);

        assertTrue(decision.verificationEligible());
        assertTrue(decision.claimQuoteSupported());
        assertTrue(decision.citationAllowed());
    }

    @Test
    void requestedCitationMustExistInBothPacketAndExactAllowlist() {
        AiNewsDecisionPolicy.Decision decision = decide(List.of(
                fact("R1", "https://yiyan.baidu.com/blog/zh/posts/dev-r1",
                        AiNewsEvidenceRelation.ENTAILS)),
                "OUT-1", false);

        assertTrue(decision.verificationEligible());
        assertFalse(decision.citationAllowed());
        assertEquals(List.of(), decision.citationIds());
        assertTrue(decision.reasonCodes().contains("REQUESTED_CITATION_NOT_ALLOWED"));
    }

    @Test
    void trustedContradictionBlocksWithoutErasingSupportingQuoteDecision() {
        AiNewsDecisionPolicy.Decision decision = decide(List.of(
                fact("R1", "https://openai.com/dev/r1", AiNewsEvidenceRelation.ENTAILS),
                fact("R2", "https://reuters.com/dev/r2", AiNewsEvidenceRelation.CONTRADICTS)),
                "R1", false);

        assertTrue(decision.claimQuoteSupported());
        assertTrue(decision.unresolvedConflict());
        assertFalse(decision.verificationEligible());
        assertTrue(decision.refusalIssued());
    }

    @Test
    void unknownTrustedRelationFailsClosed() {
        AiNewsDecisionPolicy.Decision decision = decide(List.of(
                fact("R1", "https://openai.com/dev/r1", AiNewsEvidenceRelation.UNKNOWN)),
                "R1", false);

        assertFalse(decision.verificationEligible());
        assertTrue(decision.reasonCodes().contains("MISSING_SEMANTIC_ASSESSMENT"));
    }

    @Test
    void highRiskMediaOnlySupportRequiresAnOfficialOriginal() {
        List<AiNewsDecisionPolicy.EvidenceFact> facts = List.of(
                fact("R1", "https://reuters.com/dev/r1", AiNewsEvidenceRelation.ENTAILS),
                fact("R2", "https://techcrunch.com/dev/r2", AiNewsEvidenceRelation.ENTAILS));
        AiNewsDecisionPolicy.Decision decision = policy.decide(facts, List.of("R1", "R2"),
                "R1", false, true);

        assertFalse(decision.claimQuoteSupported());
        assertFalse(decision.verificationEligible());
        assertTrue(decision.reasonCodes().contains("HIGH_RISK_REQUIRES_OFFICIAL_SUPPORT"));
    }

    @Test
    void entityEvidenceWithoutCaptureOrPublicationTimestampCannotVerify() {
        AiNewsEvidenceEntity evidence = new AiNewsEvidenceEntity();
        evidence.setId(1L);
        evidence.setSourceUrl("https://openai.com/index/model-x");
        evidence.setQuote("OpenAI released Model X to developers worldwide.");
        evidence.setSemanticRelation("entails");
        evidence.setRelationConfidence(0.95D);
        evidence.setRelationOrigin("MODEL");

        AiNewsDecisionPolicy.Decision missingCapture = policy.decideEntities(
                List.of(evidence), false, false);
        assertFalse(missingCapture.verificationEligible());
        assertTrue(missingCapture.reasonCodes().contains("MISSING_CAPTURE_PROVENANCE"));

        evidence.setFinalUrl(evidence.getSourceUrl());
        evidence.setFetchedAt(LocalDateTime.of(2026, 8, 26, 5, 0));
        evidence.setContentHash("a".repeat(64));
        evidence.setHttpStatus(200);
        evidence.setCaptureMethod("READ_ONLY_HTTP");
        AiNewsDecisionPolicy.Decision missingTimestamp = policy.decideEntities(
                List.of(evidence), false, false);
        assertFalse(missingTimestamp.verificationEligible());
        assertTrue(missingTimestamp.reasonCodes().contains("MISSING_SOURCE_TIMESTAMP"));
    }

    private AiNewsDecisionPolicy.Decision decide(List<AiNewsDecisionPolicy.EvidenceFact> facts,
                                                  String requested, boolean highRisk) {
        return policy.decide(facts, facts.stream().map(AiNewsDecisionPolicy.EvidenceFact::id).toList(),
                requested, false, highRisk);
    }

    private static AiNewsDecisionPolicy.EvidenceFact fact(String id, String url,
                                                           AiNewsEvidenceRelation relation) {
        return new AiNewsDecisionPolicy.EvidenceFact(id, url, "direct quote", relation, 0.9D, "MODEL");
    }
}

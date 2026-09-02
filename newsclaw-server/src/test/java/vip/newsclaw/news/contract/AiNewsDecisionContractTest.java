package vip.newsclaw.news.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsDecisionContractTest {

    private static final String OFFICIAL = "{\"sourceTier\":\"official\","
            + "\"verificationEligible\":true,\"citationAllowed\":true,"
            + "\"claimQuoteSupported\":true,\"refusalIssued\":false,"
            + "\"humanReviewRequested\":false,\"citationIds\":[\"E1\"]}";
    private static final String MEDIA = "{\"sourceTier\":\"media\","
            + "\"verificationEligible\":false,\"citationAllowed\":false,"
            + "\"claimQuoteSupported\":true,\"refusalIssued\":true,"
            + "\"humanReviewRequested\":true,\"citationIds\":[]}";

    private static final String FINAL_AUDIT_ASSIGNMENTS = """
            Preliminary reasoning.

            Final audit:
            1. The registered host is official; sourceTier = official.
            2. Recompute all fields from the evidence.
            - sourceTier = official. ✓
            - claimQuoteSupported = false (B = false)
            - verificationEligible = false (D = false)
            - citationAllowed = (D AND E) = (false AND false) = false
            - refusalIssued = NOT D = NOT false = true
            - humanReviewRequested = NOT citationAllowed = NOT false = true
            - citationIds = [] (because citationAllowed = false)
            All consistent.
            """;

    @Test
    void parsesOnlyACompleteInternallyCoherentDecision() {
        AiNewsDecisionContract.ParseResult valid =
                AiNewsDecisionContract.parseExactJsonObject(OFFICIAL);
        assertTrue(valid.jsonObject());
        assertTrue(valid.decisionCandidate());
        assertTrue(valid.validDecision());
        assertEquals(OFFICIAL, valid.decision().canonicalJson());

        AiNewsDecisionContract.ParseResult misspelled = AiNewsDecisionContract.parseExactJsonObject(
                OFFICIAL.replace("\"citationIds\"", "\"citationsIds\""));
        assertTrue(misspelled.jsonObject());
        assertTrue(misspelled.decisionCandidate());
        assertFalse(misspelled.validDecision());
        assertTrue(misspelled.failureReason().contains("missing required decision fields: [citationIds]"));
    }

    @Test
    void citationContextRejectsInventedAndWrongRequestedIds() {
        AiNewsDecisionContract.Decision decision =
                AiNewsDecisionContract.parseExactDecision(OFFICIAL).orElseThrow();

        assertTrue(AiNewsDecisionContract.validateCitationContext(
                decision, List.of("E1"), "E1").valid());
        assertFalse(AiNewsDecisionContract.validateCitationContext(
                decision, List.of("R1"), "R1").valid());
        assertFalse(AiNewsDecisionContract.validateCitationContext(
                decision, List.of("E1", "R1"), "R1").valid());
    }

    @Test
    void extractsTheLastWholeDecisionFromReasoningOnly() {
        String thinking = "draft {\"sourceTier\":\"official\"}\n"
                + "first candidate " + OFFICIAL + "\n"
                + "terminal candidate " + MEDIA;

        AiNewsDecisionContract.Decision extracted = AiNewsDecisionContract.extractLastDecision(thinking)
                .orElseThrow();

        assertEquals("media", extracted.sourceTier());
        assertEquals(MEDIA, extracted.canonicalJson());
        assertTrue(AiNewsDecisionContract.containsDecisionFieldSignature(
                "sourceTier verificationEligible citationAllowed claimQuoteSupported "
                        + "refusalIssued humanReviewRequested citationIds"));
        assertFalse(AiNewsDecisionContract.containsDecisionFieldSignature("sourceTier citationIds"));
    }

    @Test
    void extractsStrictAssignmentsFromTheFinalAuditSection() {
        AiNewsDecisionContract.Decision extracted = AiNewsDecisionContract.extractLastDecision(
                FINAL_AUDIT_ASSIGNMENTS).orElseThrow();

        assertEquals("official", extracted.sourceTier());
        assertFalse(extracted.verificationEligible());
        assertFalse(extracted.citationAllowed());
        assertFalse(extracted.claimQuoteSupported());
        assertTrue(extracted.refusalIssued());
        assertTrue(extracted.humanReviewRequested());
        assertEquals(List.of(), extracted.citationIds());
    }

    @Test
    void rejectsIncompleteDuplicateAndNaturalLanguageAssignmentBlocks() {
        String missing = FINAL_AUDIT_ASSIGNMENTS.replace(
                "- humanReviewRequested = NOT citationAllowed = NOT false = true\n", "");
        assertTrue(AiNewsDecisionContract.extractLastDecision(missing).isEmpty());

        String duplicate = FINAL_AUDIT_ASSIGNMENTS.replace(
                "- claimQuoteSupported = false (B = false)\n",
                "- claimQuoteSupported = false (B = false)\n"
                        + "- claimQuoteSupported = true\n");
        assertTrue(AiNewsDecisionContract.extractLastDecision(duplicate).isEmpty());

        String prose = FINAL_AUDIT_ASSIGNMENTS.replaceAll("(?m)^- ", "The ");
        assertTrue(AiNewsDecisionContract.extractLastDecision(prose).isEmpty());
    }

    @Test
    void prefersALaterStrictAssignmentAuditOverAnEarlierJsonDraft() {
        String thinking = "Draft: " + OFFICIAL + "\n\n" + FINAL_AUDIT_ASSIGNMENTS;
        AiNewsDecisionContract.Decision extracted = AiNewsDecisionContract.extractLastDecision(thinking)
                .orElseThrow();
        assertEquals("official", extracted.sourceTier());
        assertFalse(extracted.verificationEligible());
        assertFalse(extracted.citationAllowed());
    }

    @Test
    void acceptsMechanicalAuditExpressionsAndBareEvidenceIds() {
        String thinking = """
                FINAL MECHANICAL AUDIT:
                - sourceTier = A = "official"
                - claimQuoteSupported = B = true
                - verificationEligible = D = true
                - citationAllowed = (D AND E) = (true AND true) = true
                - refusalIssued = !verificationEligible = !true = false
                - humanReviewRequested = !citationAllowed = !true = false
                - citationIds = [E1] since citationAllowed = true
                """;

        AiNewsDecisionContract.Decision extracted = AiNewsDecisionContract.extractLastDecision(thinking)
                .orElseThrow();
        assertEquals("official", extracted.sourceTier());
        assertTrue(extracted.verificationEligible());
        assertTrue(extracted.citationAllowed());
        assertTrue(extracted.claimQuoteSupported());
        assertFalse(extracted.refusalIssued());
        assertFalse(extracted.humanReviewRequested());
        assertEquals(List.of("E1"), extracted.citationIds());
    }
}

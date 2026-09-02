package vip.newsclaw.news.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsEvidenceAssessmentContractTest {

    private static final String VALID = """
            {"relations":[
              {"evidenceId":"R1","relation":"entails","confidence":0.97},
              {"evidenceId":"R2","relation":"unrelated","confidence":0.84}
            ]}
            """;

    @Test
    void acceptsAllAndOnlyExpectedEvidenceIds() {
        AiNewsEvidenceAssessmentContract.ParseResult parsed =
                AiNewsEvidenceAssessmentContract.parseExact(VALID, List.of("R1", "R2"));

        assertTrue(parsed.valid());
        assertEquals(2, parsed.assessment().relations().size());
    }

    @Test
    void rejectsMissingInventedDuplicateAndUnknownRelations() {
        assertFalse(AiNewsEvidenceAssessmentContract.parseExact(VALID, List.of("R1", "R2", "R3")).valid());
        assertFalse(AiNewsEvidenceAssessmentContract.parseExact(
                VALID.replace("R2", "OUT"), List.of("R1", "R2")).valid());
        assertFalse(AiNewsEvidenceAssessmentContract.parseExact(
                VALID.replace("\"R2\"", "\"R1\""), List.of("R1", "R2")).valid());
        assertFalse(AiNewsEvidenceAssessmentContract.parseExact(
                VALID.replace("unrelated", "unknown"), List.of("R1", "R2")).valid());
    }

    @Test
    void rejectsPolicyFieldsAndNonNumericConfidence() {
        assertFalse(AiNewsEvidenceAssessmentContract.parseExact(
                VALID.replace("{\"relations\"", "{\"verificationEligible\":true,\"relations\""),
                List.of("R1", "R2")).valid());
        assertFalse(AiNewsEvidenceAssessmentContract.parseExact(
                VALID.replace("0.97", "\"0.97\""), List.of("R1", "R2")).valid());
    }

    @Test
    void rejectsACompleteButReorderedEvidenceSequence() {
        String reversed = VALID.replace("\"R1\"", "\"TMP\"")
                .replace("\"R2\"", "\"R1\"")
                .replace("\"TMP\"", "\"R2\"");

        AiNewsEvidenceAssessmentContract.ParseResult parsed =
                AiNewsEvidenceAssessmentContract.parseExact(reversed, List.of("R1", "R2"));

        assertFalse(parsed.valid());
        assertTrue(parsed.failureReason().contains("order"));
    }
}

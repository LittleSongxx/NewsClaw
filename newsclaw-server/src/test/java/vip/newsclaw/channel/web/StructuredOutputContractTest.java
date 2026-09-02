package vip.newsclaw.channel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.newsclaw.llm.chatmodel.StructuredOutputFormat;
import vip.newsclaw.llm.chatmodel.StructuredOutputSchema;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputContractTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsExactlyOneJsonObjectAndExposesMachineReadableEvidence() throws Exception {
        StructuredOutputContract.Validation result = StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, " {\"answer\":\"ok\"} ", true);

        assertTrue(result.valid());
        assertFalse(result.violatesContract());
        JsonNode payload = json.valueToTree(result.payload());
        assertTrue(payload.path("valid").asBoolean());
        assertTrue(payload.path("terminalAnswerReached").asBoolean());
        assertTrue("json_object".equals(payload.path("requestedFormat").asText()));
    }

    @Test
    void rejectsMarkdownFenceArrayAndTrailingNarrativeInsteadOfNormalizingThem() {
        StructuredOutputContract.Validation fence = StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, "```json\n{\"answer\":\"ok\"}\n```", true);
        StructuredOutputContract.Validation array = StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, "[{\"answer\":\"ok\"}]", true);
        StructuredOutputContract.Validation trailing = StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, "{\"answer\":\"ok\"} explanation", true);

        assertTrue(fence.violatesContract());
        assertTrue(array.violatesContract());
        assertTrue(trailing.violatesContract());
    }

    @Test
    void keepsGenericJsonObjectCompatibilityButValidatesAiNewsDecisionShape() {
        String validDecision = "{\"sourceTier\":\"official\","
                + "\"verificationEligible\":true,\"citationAllowed\":true,"
                + "\"claimQuoteSupported\":true,\"refusalIssued\":false,"
                + "\"humanReviewRequested\":false,\"citationIds\":[\"E1\"]}";

        assertTrue(StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, "{\"answer\":\"ok\"}", true, false).valid(),
                "json_object remains a generic object contract for non-news callers");
        assertTrue(StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, validDecision, true, true).valid());

        assertTrue(StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, "{\"sourceTier\":\"custom\"}", true, false).valid(),
                "generic callers must not be classified from coincidental output field names");
        StructuredOutputContract.Validation unrelated = StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, "{\"answer\":\"ok\"}", true, true);
        assertTrue(unrelated.violatesContract(),
                "an AI-news request cannot evade its seven-field contract with an unrelated object");
        assertTrue(unrelated.failureReason().contains("AI-news seven-field decision"));
    }

    @Test
    void rejectsIncompleteOrIncoherentAiNewsDecisionObjects() {
        String valid = "{\"sourceTier\":\"official\","
                + "\"verificationEligible\":true,\"citationAllowed\":true,"
                + "\"claimQuoteSupported\":true,\"refusalIssued\":false,"
                + "\"humanReviewRequested\":false,\"citationIds\":[\"E1\"]}";

        assertTrue(StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, valid.replace("\"citationIds\":[\"E1\"]", ""), true, true)
                .violatesContract());
        assertTrue(StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, valid.replace("\"official\"", "\"publisher\""), true, true)
                .violatesContract());
        assertTrue(StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, valid.replace("\"refusalIssued\":false", "\"refusalIssued\":true"), true, true)
                .violatesContract());
        assertTrue(StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, valid.replace("\"citationIds\":[\"E1\"]", "\"citationIds\":[\"E1\",\"E1\"]"), true, true)
                .violatesContract());
        assertTrue(StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT,
                valid.replace("\"citationIds\":[\"E1\"]", "\"citationIds\":[\" E1\"]"), true, true)
                .violatesContract());

        StructuredOutputContract.Validation misspelledCitationIds = StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT,
                valid.replace("\"citationIds\"", "\"citationsIds\""), true, true);
        assertTrue(misspelledCitationIds.violatesContract());
        assertTrue(misspelledCitationIds.failureReason().contains("missing required decision fields: [citationIds]"));
        assertTrue(misspelledCitationIds.failureReason().contains("unexpected decision fields: [citationsIds]"));
    }

    @Test
    void bindsDecisionCitationIdsToCallerAllowlistAndRequestedId() {
        String valid = "{\"sourceTier\":\"official\","
                + "\"verificationEligible\":true,\"citationAllowed\":true,"
                + "\"claimQuoteSupported\":true,\"refusalIssued\":false,"
                + "\"humanReviewRequested\":false,\"citationIds\":[\"OUT\"]}";

        StructuredOutputContract.Validation rejected = StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, valid, true,
                StructuredOutputSchema.AI_NEWS_DECISION_V1, false,
                List.of("R1"), "R1", null);

        assertTrue(rejected.violatesContract());
        assertTrue(rejected.failureReason().contains("outside the request allowlist"));
    }

    @Test
    void validatesSemanticRelationSchemaAgainstExactPacketIds() {
        String assessment = "{\"relations\":["
                + "{\"evidenceId\":\"R1\",\"relation\":\"entails\",\"confidence\":0.9},"
                + "{\"evidenceId\":\"R2\",\"relation\":\"unrelated\",\"confidence\":0.8}]}";

        assertTrue(StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, assessment, true,
                StructuredOutputSchema.AI_NEWS_EVIDENCE_RELATIONS_V2, false,
                null, null, List.of("R1", "R2")).valid());
        assertTrue(StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, assessment.replace("R2", "OUT"), true,
                StructuredOutputSchema.AI_NEWS_EVIDENCE_RELATIONS_V2, false,
                null, null, List.of("R1", "R2")).violatesContract());
    }

    @Test
    void rejectsDuplicateJsonKeysInsteadOfSilentlyTakingTheLastValue() {
        String duplicate = "{\"sourceTier\":\"official\",\"sourceTier\":\"community\","
                + "\"verificationEligible\":false,\"citationAllowed\":false,"
                + "\"claimQuoteSupported\":false,\"refusalIssued\":true,"
                + "\"humanReviewRequested\":true,\"citationIds\":[]}";

        StructuredOutputContract.Validation result = StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, duplicate, true);

        assertTrue(result.violatesContract());
    }

    @Test
    void incompleteStreamIsObservableButNotMisclassifiedAsACompletedContractViolation() {
        StructuredOutputContract.Validation result = StructuredOutputContract.validate(
                StructuredOutputFormat.JSON_OBJECT, "", false);

        assertFalse(result.valid());
        assertFalse(result.violatesContract());
        assertTrue("not_completed".equals(result.status()));
    }

    @Test
    void persistedMetadataKeepsTheValidationResult() throws Exception {
        AgentStreamAccumulator accumulator = new AgentStreamAccumulator(json, new NoopSink());
        accumulator.recordStructuredOutputContract(Map.of(
                "requestedFormat", "json_object", "status", "invalid", "valid", false));

        JsonNode metadata = json.readTree(accumulator.toMetadataJson());
        assertTrue(metadata.path("structuredOutput").path("requestedFormat")
                .asText().equals("json_object"));
        assertFalse(metadata.path("structuredOutput").path("valid").asBoolean());
    }

    private static final class NoopSink implements AgentStreamAccumulator.Sink {
        @Override
        public void broadcast(String conversationId, String eventName, Object payload) {
        }

        @Override
        public void updatePhase(String conversationId, String phase) {
        }
    }
}

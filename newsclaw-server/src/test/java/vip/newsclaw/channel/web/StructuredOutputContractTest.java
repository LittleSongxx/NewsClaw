package vip.newsclaw.channel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.newsclaw.llm.chatmodel.StructuredOutputFormat;

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

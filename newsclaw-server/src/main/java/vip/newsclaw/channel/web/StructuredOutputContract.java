package vip.newsclaw.channel.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.newsclaw.llm.chatmodel.StructuredOutputFormat;

import java.util.LinkedHashMap;
import java.util.Map;

/** Strict terminal-response validator for the opt-in Web SSE JSON contract. */
final class StructuredOutputContract {

    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StructuredOutputContract() {
    }

    static Validation validate(StructuredOutputFormat requestedFormat, String content,
                               boolean terminalAnswerReached) {
        StructuredOutputFormat format = requestedFormat == null
                ? StructuredOutputFormat.TEXT : requestedFormat;
        if (!format.requiresJsonObject()) {
            return new Validation(format, "not_requested", true, false, "");
        }
        if (!terminalAnswerReached) {
            return new Validation(format, "not_completed", false, false,
                    "stream did not reach a terminal assistant answer");
        }
        if (content == null || content.isBlank()) {
            return new Validation(format, "invalid", false, true, "assistant content is empty");
        }
        try {
            JsonNode root = STRICT_JSON.readTree(content.trim());
            if (root == null || !root.isObject()) {
                return new Validation(format, "invalid", false, true,
                        "assistant content must be exactly one JSON object");
            }
            return new Validation(format, "valid", true, true, "");
        } catch (Exception e) {
            return new Validation(format, "invalid", false, true, conciseMessage(e));
        }
    }

    static Validation notCompleted(StructuredOutputFormat requestedFormat, String reason) {
        StructuredOutputFormat format = requestedFormat == null
                ? StructuredOutputFormat.TEXT : requestedFormat;
        return new Validation(format, format.requiresJsonObject() ? "not_completed" : "not_requested",
                !format.requiresJsonObject(), false,
                reason == null || reason.isBlank() ? "stream did not reach a terminal assistant answer" : reason);
    }

    private static String conciseMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "invalid JSON object";
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    record Validation(StructuredOutputFormat requestedFormat, String status, boolean valid,
                      boolean terminalAnswerReached, String failureReason) {
        boolean violatesContract() {
            return requestedFormat != null && requestedFormat.requiresJsonObject()
                    && terminalAnswerReached && !valid;
        }

        Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("requestedFormat", requestedFormat == null ? "text" : requestedFormat.wireValue());
            payload.put("enforcement", "native_response_format_and_server_validation");
            payload.put("status", status);
            payload.put("valid", valid);
            payload.put("terminalAnswerReached", terminalAnswerReached);
            payload.put("failureReason", failureReason == null ? "" : failureReason);
            return payload;
        }
    }
}

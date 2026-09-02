package vip.newsclaw.channel.web;

import vip.newsclaw.llm.chatmodel.StructuredOutputFormat;
import vip.newsclaw.llm.chatmodel.StructuredOutputSchema;
import vip.newsclaw.news.contract.AiNewsDecisionContract;
import vip.newsclaw.news.contract.AiNewsEvidenceAssessmentContract;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict terminal-response validator for the opt-in Web SSE JSON contract. */
final class StructuredOutputContract {

    private StructuredOutputContract() {
    }

    static Validation validate(StructuredOutputFormat requestedFormat, String content,
                               boolean terminalAnswerReached) {
        return validate(requestedFormat, content, terminalAnswerReached, false);
    }

    /**
     * Validate the generic JSON-object contract and, when explicitly selected
     * by the request schema (or the legacy prompt-signature fallback), the
     * stricter AI-news decision contract.
     */
    static Validation validate(StructuredOutputFormat requestedFormat, String content,
                               boolean terminalAnswerReached,
                               boolean aiNewsDecisionRequired) {
        return validate(requestedFormat, content, terminalAnswerReached,
                aiNewsDecisionRequired ? StructuredOutputSchema.AI_NEWS_DECISION_V1
                        : StructuredOutputSchema.GENERIC,
                false, null, null, null);
    }

    static Validation validate(StructuredOutputFormat requestedFormat, String content,
                               boolean terminalAnswerReached,
                               StructuredOutputSchema requestedSchema,
                               boolean legacyAiNewsDecisionRequired,
                               Collection<String> allowedCitationIds,
                               String requestedCitationId,
                               Collection<String> expectedEvidenceIds) {
        StructuredOutputFormat format = requestedFormat == null
                ? StructuredOutputFormat.TEXT : requestedFormat;
        StructuredOutputSchema schema = requestedSchema == null
                ? StructuredOutputSchema.GENERIC : requestedSchema;
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
        if (schema.requiresAiNewsEvidenceRelations()) {
            AiNewsEvidenceAssessmentContract.ParseResult assessment =
                    AiNewsEvidenceAssessmentContract.parseExact(content, expectedEvidenceIds);
            if (!assessment.valid()) {
                return new Validation(format, "invalid", false, true, assessment.failureReason());
            }
            return new Validation(format, "valid", true, true, "");
        }
        AiNewsDecisionContract.ParseResult result = AiNewsDecisionContract.parseExactJsonObject(content);
        if (!result.jsonObject()) {
            return new Validation(format, "invalid", false, true, result.failureReason());
        }
        boolean aiNewsDecisionRequired = schema.requiresAiNewsDecision()
                || legacyAiNewsDecisionRequired;
        if (aiNewsDecisionRequired && !result.validDecision()) {
            String reason = result.failureReason();
            if (reason == null || reason.isBlank()) {
                reason = "assistant content must be exactly one valid AI-news seven-field decision";
            }
            return new Validation(format, "invalid", false, true, reason);
        }
        if (aiNewsDecisionRequired) {
            AiNewsDecisionContract.ContextValidation context =
                    AiNewsDecisionContract.validateCitationContext(
                            result.decision(), allowedCitationIds, requestedCitationId);
            if (!context.valid()) {
                return new Validation(format, "invalid", false, true, context.failureReason());
            }
        }
        return new Validation(format, "valid", true, true, "");
    }

    static Validation notCompleted(StructuredOutputFormat requestedFormat, String reason) {
        StructuredOutputFormat format = requestedFormat == null
                ? StructuredOutputFormat.TEXT : requestedFormat;
        return new Validation(format, format.requiresJsonObject() ? "not_completed" : "not_requested",
                !format.requiresJsonObject(), false,
                reason == null || reason.isBlank() ? "stream did not reach a terminal assistant answer" : reason);
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

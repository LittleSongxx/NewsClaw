package vip.newsclaw.llm.chatmodel;

import java.util.Locale;

/**
 * Optional semantic schema layered on top of a structured response format.
 *
 * <p>{@link #GENERIC} preserves the existing {@code json_object} behavior.
 * A named schema lets callers opt into server-side field and invariant
 * validation without relying on prompt-text inspection.</p>
 */
public enum StructuredOutputSchema {

    GENERIC("generic"),
    AI_NEWS_DECISION_V1("ai_news_decision_v1"),
    AI_NEWS_EVIDENCE_RELATIONS_V2("ai_news_evidence_relations_v2");

    private final String wireValue;

    StructuredOutputSchema(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public boolean requiresAiNewsDecision() {
        return this == AI_NEWS_DECISION_V1;
    }

    public boolean requiresAiNewsEvidenceRelations() {
        return this == AI_NEWS_EVIDENCE_RELATIONS_V2;
    }

    public boolean isNamedSchema() {
        return this != GENERIC;
    }

    /** Null/blank keeps the legacy generic JSON-object contract. */
    public static StructuredOutputSchema fromWire(String raw) {
        if (raw == null || raw.isBlank()) return GENERIC;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (StructuredOutputSchema schema : values()) {
            if (schema.wireValue.equals(value)) return schema;
        }
        throw new IllegalArgumentException(
                "responseSchema must be generic, ai_news_decision_v1, or ai_news_evidence_relations_v2");
    }
}

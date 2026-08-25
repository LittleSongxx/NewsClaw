package vip.newsclaw.llm.chatmodel;

import java.util.Locale;

/**
 * Explicit response contract requested by a chat caller.
 *
 * <p>The default is deliberately text so existing Web, IM and API clients keep
 * their current behavior. {@link #JSON_OBJECT} is opt-in and must be backed by
 * both a compatible model request and server-side terminal-output validation.
 */
public enum StructuredOutputFormat {

    TEXT("text"),
    JSON_OBJECT("json_object");

    private final String wireValue;

    StructuredOutputFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public boolean requiresJsonObject() {
        return this == JSON_OBJECT;
    }

    /** Null/blank preserves the legacy text response mode. */
    public static StructuredOutputFormat fromWire(String raw) {
        if (raw == null || raw.isBlank()) return TEXT;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (StructuredOutputFormat format : values()) {
            if (format.wireValue.equals(value)) return format;
        }
        throw new IllegalArgumentException("responseFormat must be text or json_object");
    }
}

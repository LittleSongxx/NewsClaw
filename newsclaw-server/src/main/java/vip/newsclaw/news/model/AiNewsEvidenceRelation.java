package vip.newsclaw.news.model;

import java.util.Locale;

/**
 * Semantic relationship between one archived quote and the claim stored on
 * the same evidence row.
 *
 * <p>The model is allowed to judge this narrow relationship. Source trust,
 * corroboration, conflict blocking, citation permission and lifecycle state
 * remain deterministic server decisions.</p>
 */
public enum AiNewsEvidenceRelation {
    ENTAILS("entails"),
    CONTRADICTS("contradicts"),
    PARTIAL("partial"),
    UNRELATED("unrelated"),
    HEDGED("hedged"),
    UNKNOWN("unknown");

    private final String token;

    AiNewsEvidenceRelation(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public boolean supportsClaim() {
        return this == ENTAILS;
    }

    public boolean contradictsClaim() {
        return this == CONTRADICTS;
    }

    public boolean needsAssessment() {
        return this == UNKNOWN;
    }

    public static AiNewsEvidenceRelation from(String value) {
        if (value == null || value.isBlank()) return UNKNOWN;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (AiNewsEvidenceRelation relation : values()) {
            if (relation.token.equals(normalized)) return relation;
        }
        throw new IllegalArgumentException("unknown evidence relation: " + value);
    }
}

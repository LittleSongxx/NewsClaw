package vip.newsclaw.news.model;

/** Lifecycle for an AI-industry event from discovery to editorial delivery. */
public enum AiNewsEventStatus {
    CANDIDATE("candidate"),
    RESEARCHING("researching"),
    VERIFIED("verified"),
    CONFLICTED("conflicted"),
    REJECTED("rejected"),
    IN_PRODUCTION("in_production"),
    PUBLISHED("published"),
    ARCHIVED("archived");

    private final String token;

    AiNewsEventStatus(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static AiNewsEventStatus from(String value) {
        if (value == null || value.isBlank()) return CANDIDATE;
        for (AiNewsEventStatus status : values()) {
            if (status.token.equalsIgnoreCase(value.trim())) return status;
        }
        throw new IllegalArgumentException("unknown AI news event status: " + value);
    }
}

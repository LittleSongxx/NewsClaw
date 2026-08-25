package vip.newsclaw.news.model;

/** Lifecycle of a deterministic AI-news human-review task. */
public enum AiNewsReviewTaskStatus {
    PENDING,
    RESOLVED,
    NO_LONGER_REQUIRED;

    public static AiNewsReviewTaskStatus from(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

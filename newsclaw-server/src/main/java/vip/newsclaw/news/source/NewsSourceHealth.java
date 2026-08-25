package vip.newsclaw.news.source;

import java.time.Instant;

/** Provider health snapshot suitable for an operations panel. */
public record NewsSourceHealth(
        String providerId,
        boolean available,
        String status,
        String message,
        Instant checkedAt,
        long latencyMs
) {
    public static NewsSourceHealth disabled(String providerId, String message) {
        return new NewsSourceHealth(providerId, false, "disabled", message, Instant.now(), 0L);
    }

    public static NewsSourceHealth healthy(String providerId, long latencyMs) {
        return new NewsSourceHealth(providerId, true, "healthy", "ok", Instant.now(), latencyMs);
    }
}

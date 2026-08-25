package vip.newsclaw.news.source;

import java.time.Instant;
import java.util.Map;

/**
 * Retrieval metadata that must travel with a provider result. It is not a
 * verification verdict: callers still have to pass the result through
 * AiNewsEventService's evidence and lifecycle gates.
 */
public record NewsSourceProvenance(
        String providerId,
        String sourceTier,
        String sourceUrl,
        String canonicalUrl,
        Instant fetchedAt,
        Integer httpStatus,
        String retrievalMethod,
        Map<String, Object> metadata
) {
    public NewsSourceProvenance {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        fetchedAt = fetchedAt == null ? Instant.now() : fetchedAt;
    }
}

package vip.newsclaw.news.source;

import java.time.Instant;

/** Provider-neutral search request. */
public record NewsSourceQuery(
        String query,
        int limit,
        String language,
        Instant since
) {
    public NewsSourceQuery {
        query = query == null ? "" : query.trim();
        limit = Math.min(Math.max(limit <= 0 ? 10 : limit, 1), 100);
        language = language == null ? "" : language.trim();
    }

    public NewsSourceQuery(String query, int limit) {
        this(query, limit, null, null);
    }
}

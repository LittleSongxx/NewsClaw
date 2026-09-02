package vip.newsclaw.news.source;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/** Bounded transport observation captured before parsing or relevance filtering. */
public record NewsSourceTransportRecord(
        URI requestUrl,
        URI finalUrl,
        Integer httpStatus,
        String contentType,
        String etag,
        String lastModified,
        String retryAfter,
        Long declaredContentLength,
        byte[] body,
        boolean truncated,
        boolean notModified,
        Instant startedAt,
        Instant finishedAt,
        String errorCode,
        String errorMessage
) {

    public NewsSourceTransportRecord {
        if (requestUrl == null) throw new IllegalArgumentException("requestUrl is required");
        finalUrl = finalUrl == null ? requestUrl : finalUrl;
        contentType = safe(contentType);
        etag = safe(etag);
        lastModified = safe(lastModified);
        retryAfter = safe(retryAfter);
        body = body == null ? new byte[0] : body.clone();
        startedAt = startedAt == null ? Instant.now() : startedAt;
        finishedAt = finishedAt == null ? startedAt : finishedAt;
        if (finishedAt.isBefore(startedAt)) finishedAt = startedAt;
        errorCode = safe(errorCode);
        errorMessage = safe(errorMessage);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public long receivedBytes() {
        return body.length;
    }

    public long durationMs() {
        return Math.max(0L, Duration.between(startedAt, finishedAt).toMillis());
    }

    public boolean succeeded() {
        return errorCode.isBlank() && httpStatus != null
                && (httpStatus == 304 || httpStatus >= 200 && httpStatus < 300);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

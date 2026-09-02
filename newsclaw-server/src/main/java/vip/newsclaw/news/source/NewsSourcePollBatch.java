package vip.newsclaw.news.source;

import java.time.Instant;
import java.util.List;

/** Result of polling one endpoint, including normalized items and every HTTP observation. */
public record NewsSourcePollBatch(
        NewsSourceEndpointDescriptor endpoint,
        Status status,
        Instant startedAt,
        Instant finishedAt,
        List<NewsSourceResult> results,
        List<NewsSourceTransportRecord> transports,
        String errorCode,
        String errorMessage
) {

    public NewsSourcePollBatch {
        endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
        status = status == null ? Status.FAILED : status;
        startedAt = startedAt == null ? Instant.now() : startedAt;
        finishedAt = finishedAt == null ? startedAt : finishedAt;
        if (finishedAt.isBefore(startedAt)) finishedAt = startedAt;
        results = results == null ? List.of() : List.copyOf(results);
        transports = transports == null ? List.of() : List.copyOf(transports);
        errorCode = errorCode == null ? "" : errorCode.trim();
        errorMessage = errorMessage == null ? "" : errorMessage.trim();
    }

    public static NewsSourcePollBatch failed(NewsSourceEndpointDescriptor endpoint,
                                             Instant startedAt,
                                             NewsSourceTransportRecord transport,
                                             String errorCode,
                                             String errorMessage) {
        return new NewsSourcePollBatch(endpoint, Status.FAILED, startedAt, Instant.now(),
                List.of(), transport == null ? List.of() : List.of(transport),
                errorCode, errorMessage);
    }

    public enum Status {
        SUCCESS,
        NOT_MODIFIED,
        DEGRADED,
        FAILED
    }
}

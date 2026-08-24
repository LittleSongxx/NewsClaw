package vip.mate.news.model;

import java.time.LocalDateTime;

/** Immutable trace emitted by the official-source read-only capture boundary. */
public record AiNewsEvidenceCaptureTrace(
        String finalUrl,
        LocalDateTime fetchedAt,
        String contentHash,
        Integer httpStatus,
        String captureMethod,
        String redirectChainJson
) {
}

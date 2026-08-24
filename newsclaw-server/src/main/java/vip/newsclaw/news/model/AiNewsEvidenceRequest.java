package vip.newsclaw.news.model;

import java.time.LocalDateTime;

/** Input packet emitted by the radar/verification agents. */
public record AiNewsEvidenceRequest(
        String sourceUrl,
        String sourceTitle,
        LocalDateTime sourcePublishedAt,
        String sourceTier,
        String claim,
        String quote,
        Double confidence) {
}

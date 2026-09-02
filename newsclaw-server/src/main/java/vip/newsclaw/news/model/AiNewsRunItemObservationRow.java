package vip.newsclaw.news.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Read-only joined projection used by the ingestion operations API. */
@Data
public class AiNewsRunItemObservationRow {
    private Long observationId;
    private Long sourceItemId;
    private Long sourceItemVersionId;
    private String observationOutcome;
    private LocalDateTime observedAt;
    private String externalItemId;
    private String canonicalUrl;
    private String sourceUrl;
    private String sourceTier;
    private String versionHash;
    private String title;
    private LocalDateTime sourcePublishedAt;
}

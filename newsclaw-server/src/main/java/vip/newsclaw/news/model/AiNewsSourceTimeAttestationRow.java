package vip.newsclaw.news.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Joined read projection used to bind publisher feed/sitemap time to an article capture. */
@Data
public class AiNewsSourceTimeAttestationRow {

    private Long sourceItemId;
    private Long sourceItemVersionId;
    private Long ingestionRunId;
    private String versionHash;
    private String canonicalUrl;
    private String sourceUrl;
    private String sourceTier;
    private LocalDateTime sourcePublishedAt;
    private String publishedAtRaw;
    private String provenanceJson;
    private LocalDateTime observedAt;
    private Long endpointId;
    private String endpointKey;
    private Integer catalogVersion;
    private String endpointSourceKey;
    private String providerId;
    private String adapter;
    private String endpointUrl;
    private Boolean endpointEnabled;
    private Boolean evidenceEligible;
    private String rightsStatus;
    private String robotsStatus;
    private String runStatus;
}

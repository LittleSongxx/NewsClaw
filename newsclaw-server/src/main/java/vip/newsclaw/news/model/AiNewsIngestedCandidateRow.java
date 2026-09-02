package vip.newsclaw.news.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Read projection for the latest persisted version of a structured source item. */
@Data
public class AiNewsIngestedCandidateRow {
    private String title;
    private String snippet;
    private String content;
    private String provenanceJson;
    private String sourceUrl;
    private String canonicalUrl;
    private String sourceTier;
    private String providerId;
    private LocalDateTime firstObservedAt;
    private LocalDateTime lastObservedAt;
}

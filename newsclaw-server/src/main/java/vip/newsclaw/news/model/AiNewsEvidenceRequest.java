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
        Double confidence,
        String semanticRelation,
        Double relationConfidence,
        Long captureId) {

    /** Compatibility constructor for API/manual evidence without a capture binding. */
    public AiNewsEvidenceRequest(String sourceUrl,
                                 String sourceTitle,
                                 LocalDateTime sourcePublishedAt,
                                 String sourceTier,
                                 String claim,
                                 String quote,
                                 Double confidence,
                                 String semanticRelation,
                                 Double relationConfidence) {
        this(sourceUrl, sourceTitle, sourcePublishedAt, sourceTier, claim, quote,
                confidence, semanticRelation, relationConfidence, null);
    }

    /** Compatibility constructor for callers that have not assessed semantics yet. */
    public AiNewsEvidenceRequest(String sourceUrl,
                                 String sourceTitle,
                                 LocalDateTime sourcePublishedAt,
                                 String sourceTier,
                                 String claim,
                                 String quote,
                                 Double confidence) {
        this(sourceUrl, sourceTitle, sourcePublishedAt, sourceTier, claim, quote,
                confidence, AiNewsEvidenceRelation.UNKNOWN.token(), null, null);
    }
}

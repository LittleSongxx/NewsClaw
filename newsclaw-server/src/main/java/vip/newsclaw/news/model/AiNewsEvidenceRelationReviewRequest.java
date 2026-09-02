package vip.newsclaw.news.model;

/** Explicit operator attestation for one claim-to-evidence relationship. */
public record AiNewsEvidenceRelationReviewRequest(
        String semanticRelation,
        Double confidence,
        String note) {
}

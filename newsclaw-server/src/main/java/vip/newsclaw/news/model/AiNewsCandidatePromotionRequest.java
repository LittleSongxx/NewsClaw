package vip.newsclaw.news.model;

import java.util.List;

/** Human-supplied fact packet used to move one accepted candidate into events. */
public record AiNewsCandidatePromotionRequest(
        String claim,
        String quote,
        String category,
        List<String> entities,
        String semanticRelation,
        Double relationConfidence) {
}

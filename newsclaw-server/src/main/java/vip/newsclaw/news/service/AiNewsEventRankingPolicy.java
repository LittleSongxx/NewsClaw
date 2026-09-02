package vip.newsclaw.news.service;

import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEvidenceRelation;
import vip.newsclaw.news.model.AiNewsEventEntity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Observable-quality ranking only; no model-provided importance score is consumed. */
final class AiNewsEventRankingPolicy {

    private AiNewsEventRankingPolicy() {
    }

    static double score(AiNewsEventEntity event, List<AiNewsEvidenceEntity> evidence,
                        AiNewsSourceRegistry registry) {
        List<AiNewsEvidenceEntity> rows = evidence == null ? List.of() : evidence.stream()
                .filter(item -> item != null && (item.getDeleted() == null || item.getDeleted() == 0)).toList();
        boolean official = rows.stream().anyMatch(item -> registry.isOfficialUrl(item.getSourceUrl()));
        Set<String> media = new LinkedHashSet<>();
        rows.stream().map(AiNewsEvidenceEntity::getSourceUrl)
                .map(registry::trustedMediaSourceKey).flatMap(java.util.Optional::stream)
                .forEach(media::add);
        boolean captureBound = rows.stream().anyMatch(AiNewsEventRankingPolicy::captureBound);
        boolean timestamped = rows.stream().anyMatch(item -> item.getSourcePublishedAt() != null);
        boolean attestedSupport = rows.stream().anyMatch(item -> supports(item)
                && AiNewsRelationAttestation.isVerificationAttested(item.getRelationOrigin()));
        boolean modelSupport = rows.stream().anyMatch(AiNewsEventRankingPolicy::supports);
        boolean conflict = rows.stream().anyMatch(item -> AiNewsEvidenceRelation
                .from(item.getSemanticRelation()).contradictsClaim());

        double score = 0.0D;
        score += official ? 0.34D : !media.isEmpty() ? 0.22D : rows.isEmpty() ? 0.0D : 0.05D;
        if (media.size() >= 2) score += 0.12D;
        if (captureBound) score += 0.18D;
        if (timestamped) score += 0.10D;
        if (attestedSupport) score += 0.20D;
        else if (modelSupport) score += 0.07D;
        if (event != null && event.getClaimsJson() != null && !"[]".equals(event.getClaimsJson())) {
            score += 0.04D;
        }
        if (conflict) score -= 0.50D;
        return Math.max(0.0D, Math.min(1.0D, Math.round(score * 10_000D) / 10_000D));
    }

    private static boolean supports(AiNewsEvidenceEntity item) {
        return item != null && item.getQuote() != null && !item.getQuote().isBlank()
                && AiNewsEvidenceRelation.from(item.getSemanticRelation()).supportsClaim();
    }

    private static boolean captureBound(AiNewsEvidenceEntity item) {
        return item.getSourceCaptureId() != null && item.getFetchedAt() != null
                && item.getHttpStatus() != null && item.getHttpStatus() >= 200 && item.getHttpStatus() < 300
                && item.getContentHash() != null && item.getContentHash().length() == 64
                && item.getFinalUrl() != null && !item.getFinalUrl().isBlank();
    }
}

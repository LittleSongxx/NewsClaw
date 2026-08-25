package vip.newsclaw.channel.feishu.cards.ai_news;

import java.util.List;

/** Immutable, bounded projection rendered into one event-review card. */
public record AiNewsReviewCardPayload(
        Long workspaceId,
        String requesterOpenId,
        Long eventId,
        String title,
        String summary,
        String category,
        String status,
        Double confidence,
        int evidenceCount,
        int verifiedEvidenceCount,
        String primaryEvidenceTier,
        boolean reviewRequired,
        List<String> reviewReasons
) {
}

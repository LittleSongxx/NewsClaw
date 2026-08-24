package vip.newsclaw.news.model;

import java.time.LocalDateTime;
import java.util.List;

/** Candidate event input. A canonical source URL is mandatory for traceability. */
public record AiNewsEventUpsertRequest(
        String eventKey,
        String title,
        String summary,
        String category,
        List<String> entities,
        LocalDateTime discoveredAt,
        LocalDateTime publishedAt,
        List<String> claims,
        List<String> conflicts,
        List<AiNewsEvidenceRequest> evidence) {
}

package vip.mate.news.model;

import java.util.List;

/** API read model combining an event, valid evidence, and capture-attempt audit trail. */
public record AiNewsEventDetail(AiNewsEventEntity event,
                                List<AiNewsEvidenceEntity> evidence,
                                List<AiNewsCaptureAttemptEntity> captureAttempts) {
}

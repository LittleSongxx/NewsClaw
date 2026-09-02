ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN semantic_relation VARCHAR(16) NOT NULL DEFAULT 'unknown' AFTER quote,
    ADD COLUMN relation_confidence DOUBLE NULL AFTER semantic_relation,
    ADD COLUMN relation_origin VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER relation_confidence,
    ADD COLUMN relation_reviewed_at DATETIME NULL AFTER relation_origin,
    ADD COLUMN relation_reviewed_by VARCHAR(256) NULL AFTER relation_reviewed_at,
    ADD COLUMN relation_review_note TEXT NULL AFTER relation_reviewed_by;

CREATE INDEX idx_ai_news_evidence_relation
    ON mate_ai_news_event_evidence(workspace_id, event_id, semantic_relation);

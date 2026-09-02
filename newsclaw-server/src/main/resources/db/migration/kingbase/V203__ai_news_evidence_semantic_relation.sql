ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS semantic_relation VARCHAR(16) NOT NULL DEFAULT 'unknown';
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS relation_confidence DOUBLE PRECISION;
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS relation_origin VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS relation_reviewed_at TIMESTAMP(3);
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS relation_reviewed_by VARCHAR(256);
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS relation_review_note TEXT;

CREATE INDEX IF NOT EXISTS idx_ai_news_evidence_relation
    ON mate_ai_news_event_evidence(workspace_id, event_id, semantic_relation);

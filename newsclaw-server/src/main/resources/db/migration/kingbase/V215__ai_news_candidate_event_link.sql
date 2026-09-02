-- Explicit, auditable bridge from an accepted candidate to one event.
ALTER TABLE mate_ai_news_candidate
    ADD COLUMN IF NOT EXISTS event_id BIGINT;
ALTER TABLE mate_ai_news_candidate
    ADD COLUMN IF NOT EXISTS promoted_at TIMESTAMP(3);
CREATE INDEX IF NOT EXISTS idx_ai_news_candidate_event
    ON mate_ai_news_candidate(workspace_id, event_id);

ALTER TABLE mate_ai_news_event
    ADD COLUMN IF NOT EXISTS ranking_score DOUBLE DEFAULT 0.0;

CREATE INDEX IF NOT EXISTS idx_ai_news_event_quality_rank
    ON mate_ai_news_event(workspace_id, status, ranking_score, published_at);

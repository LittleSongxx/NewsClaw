ALTER TABLE mate_ai_news_event
    ADD COLUMN ranking_score DOUBLE NULL DEFAULT 0.0 AFTER confidence;

CREATE INDEX idx_ai_news_event_quality_rank
    ON mate_ai_news_event(workspace_id, status, ranking_score, published_at);

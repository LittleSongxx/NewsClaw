ALTER TABLE mate_ai_news_event
    ADD COLUMN IF NOT EXISTS source_published_at TIMESTAMP;

UPDATE mate_ai_news_event e
SET source_published_at = (
    SELECT MIN(evidence.source_published_at)
    FROM mate_ai_news_event_evidence evidence
    WHERE evidence.event_id = e.id
      AND evidence.workspace_id = e.workspace_id
      AND evidence.deleted = 0
      AND evidence.source_published_at IS NOT NULL
)
WHERE EXISTS (
    SELECT 1
    FROM mate_ai_news_event_evidence evidence
    WHERE evidence.event_id = e.id
      AND evidence.workspace_id = e.workspace_id
      AND evidence.deleted = 0
      AND evidence.source_published_at IS NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_news_event_source_window
    ON mate_ai_news_event(workspace_id, source_published_at);

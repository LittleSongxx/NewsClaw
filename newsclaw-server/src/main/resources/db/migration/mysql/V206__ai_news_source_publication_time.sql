ALTER TABLE mate_ai_news_event
    ADD COLUMN source_published_at DATETIME NULL AFTER discovered_at;

UPDATE mate_ai_news_event e
JOIN (
    SELECT event_id, workspace_id, MIN(source_published_at) AS first_source_published_at
    FROM mate_ai_news_event_evidence
    WHERE deleted = 0 AND source_published_at IS NOT NULL
    GROUP BY event_id, workspace_id
) evidence_time
  ON evidence_time.event_id = e.id
 AND evidence_time.workspace_id = e.workspace_id
SET e.source_published_at = evidence_time.first_source_published_at;

CREATE INDEX idx_ai_news_event_source_window
    ON mate_ai_news_event(workspace_id, source_published_at);

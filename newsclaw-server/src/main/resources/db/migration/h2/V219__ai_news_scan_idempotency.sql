ALTER TABLE mate_ai_news_scan_run ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);
ALTER TABLE mate_ai_news_scan_run ADD COLUMN IF NOT EXISTS active_slot INT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_scan_idempotency
    ON mate_ai_news_scan_run(workspace_id, idempotency_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_scan_active
    ON mate_ai_news_scan_run(workspace_id, active_slot);

ALTER TABLE mate_ai_news_scan_run ADD COLUMN idempotency_key VARCHAR(64) NULL;
ALTER TABLE mate_ai_news_scan_run ADD COLUMN active_slot INT NULL;
CREATE UNIQUE INDEX uk_ai_news_scan_idempotency
    ON mate_ai_news_scan_run(workspace_id, idempotency_key);
CREATE UNIQUE INDEX uk_ai_news_scan_active
    ON mate_ai_news_scan_run(workspace_id, active_slot);

ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN final_url VARCHAR(4096) NULL,
    ADD COLUMN fetched_at DATETIME NULL,
    ADD COLUMN content_hash VARCHAR(64) NULL,
    ADD COLUMN http_status INT NULL,
    ADD COLUMN capture_method VARCHAR(32) NULL,
    ADD COLUMN redirect_chain_json MEDIUMTEXT NULL;

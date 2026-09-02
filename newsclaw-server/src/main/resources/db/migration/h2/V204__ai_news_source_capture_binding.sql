CREATE TABLE IF NOT EXISTS mate_ai_news_source_capture (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    source_url VARCHAR(4096) NOT NULL,
    source_url_hash VARCHAR(64) NOT NULL,
    final_url VARCHAR(4096),
    source_title VARCHAR(512),
    source_published_at TIMESTAMP,
    published_at_raw VARCHAR(512),
    published_at_method VARCHAR(64),
    source_tier VARCHAR(16) NOT NULL DEFAULT 'community',
    http_status INT,
    fetched_at TIMESTAMP,
    content_hash VARCHAR(64),
    content_type VARCHAR(256),
    capture_method VARCHAR(32) NOT NULL DEFAULT 'READ_ONLY_HTTP',
    redirect_chain_json CLOB,
    extracted_text CLOB,
    extracted_text_hash VARCHAR(64),
    text_length INT,
    capture_status VARCHAR(32) NOT NULL DEFAULT 'started',
    capture_error VARCHAR(2000),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ai_news_capture_workspace_fetched
    ON mate_ai_news_source_capture(workspace_id, fetched_at);
CREATE INDEX IF NOT EXISTS idx_ai_news_capture_workspace_url
    ON mate_ai_news_source_capture(workspace_id, source_url_hash);

ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS source_capture_id BIGINT;
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS quote_start INT;
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS quote_end INT;
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS quote_match_method VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_ai_news_evidence_capture
    ON mate_ai_news_event_evidence(workspace_id, source_capture_id);

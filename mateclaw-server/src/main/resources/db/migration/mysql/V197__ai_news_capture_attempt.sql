CREATE TABLE IF NOT EXISTS mate_ai_news_capture_attempt (
    id BIGINT NOT NULL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    source_url VARCHAR(4096) NOT NULL,
    final_url VARCHAR(4096) NULL,
    capture_status VARCHAR(32) NOT NULL,
    capture_error VARCHAR(2000) NULL,
    http_status INT NULL,
    capture_method VARCHAR(32) NOT NULL DEFAULT 'READ_ONLY_HTTP',
    redirect_chain_json MEDIUMTEXT NULL,
    attempted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    INDEX idx_ai_news_capture_workspace_event (workspace_id, event_id, attempted_at)
);

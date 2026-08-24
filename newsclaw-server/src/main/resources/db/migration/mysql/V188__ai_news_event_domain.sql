-- AI news operations: time-sensitive events and their source evidence.
CREATE TABLE IF NOT EXISTS mate_ai_news_event (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    event_key VARCHAR(64) NOT NULL,
    title VARCHAR(512) NOT NULL,
    summary TEXT,
    category VARCHAR(32) NOT NULL DEFAULT 'model',
    entities_json TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'candidate',
    confidence DOUBLE DEFAULT 0.0,
    claims_json TEXT,
    conflicts_json TEXT,
    discovered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME,
    wiki_page_id BIGINT,
    team_run_id BIGINT,
    gzh_content_item_id BIGINT,
    xhs_content_item_id BIGINT,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_ai_news_event_workspace_key
    ON mate_ai_news_event(workspace_id, event_key);
CREATE INDEX idx_ai_news_event_workspace_status
    ON mate_ai_news_event(workspace_id, status, discovered_at);
CREATE INDEX idx_ai_news_event_category
    ON mate_ai_news_event(workspace_id, category, discovered_at);

CREATE TABLE IF NOT EXISTS mate_ai_news_event_evidence (
    id BIGINT NOT NULL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    source_url VARCHAR(2048) NOT NULL,
    source_title VARCHAR(512),
    source_published_at DATETIME,
    source_tier VARCHAR(16) NOT NULL DEFAULT 'media',
    claim TEXT NOT NULL,
    quote TEXT,
    confidence DOUBLE DEFAULT 0.0,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
-- Do not index the full VARCHAR(2048) URL here: utf8mb4 makes that key
-- exceed InnoDB's maximum index width before V190 can add the fixed-width
-- SHA-256 key. V190 creates the unique (event_id, source_url_hash) index;
-- the workspace/event index below is sufficient during the short upgrade
-- window and keeps this first migration valid on a default utf8mb4 server.
CREATE INDEX idx_ai_news_evidence_workspace_event
    ON mate_ai_news_event_evidence(workspace_id, event_id);

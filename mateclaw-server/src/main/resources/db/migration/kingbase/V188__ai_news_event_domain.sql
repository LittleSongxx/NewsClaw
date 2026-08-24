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
    confidence DOUBLE PRECISION DEFAULT 0.0,
    claims_json TEXT,
    conflicts_json TEXT,
    discovered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    wiki_page_id BIGINT,
    team_run_id BIGINT,
    gzh_content_item_id BIGINT,
    xhs_content_item_id BIGINT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_event_workspace_key
    ON mate_ai_news_event(workspace_id, event_key);
CREATE INDEX IF NOT EXISTS idx_ai_news_event_workspace_status
    ON mate_ai_news_event(workspace_id, status, discovered_at);
CREATE INDEX IF NOT EXISTS idx_ai_news_event_category
    ON mate_ai_news_event(workspace_id, category, discovered_at);

CREATE TABLE IF NOT EXISTS mate_ai_news_event_evidence (
    id BIGINT NOT NULL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL DEFAULT 1,
    source_url VARCHAR(2048) NOT NULL,
    source_title VARCHAR(512),
    source_published_at TIMESTAMP,
    source_tier VARCHAR(16) NOT NULL DEFAULT 'media',
    claim TEXT NOT NULL,
    quote TEXT,
    confidence DOUBLE PRECISION DEFAULT 0.0,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_evidence_source
    ON mate_ai_news_event_evidence(event_id, source_url);
CREATE INDEX IF NOT EXISTS idx_ai_news_evidence_workspace_event
    ON mate_ai_news_event_evidence(workspace_id, event_id);

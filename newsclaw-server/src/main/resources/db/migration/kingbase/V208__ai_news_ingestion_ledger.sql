-- Persistent, replay-oriented acquisition ledger for structured AI-news sources.
CREATE TABLE IF NOT EXISTS mate_ai_news_source_endpoint (
    id BIGINT NOT NULL PRIMARY KEY,
    endpoint_key VARCHAR(160) NOT NULL,
    catalog_version INT NOT NULL DEFAULT 0,
    source_key VARCHAR(128),
    provider_id VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    adapter VARCHAR(32) NOT NULL,
    endpoint_url VARCHAR(4096) NOT NULL,
    endpoint_url_hash VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    languages_json TEXT,
    categories_json TEXT,
    poll_interval_seconds INT NOT NULL DEFAULT 900,
    evidence_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    rights_status VARCHAR(64) NOT NULL DEFAULT 'operator_managed',
    raw_retention VARCHAR(32) NOT NULL DEFAULT 'metadata_only',
    robots_status VARCHAR(64) NOT NULL DEFAULT 'operator_managed',
    etag VARCHAR(1024),
    last_modified VARCHAR(1024),
    last_attempt_at TIMESTAMP(3),
    last_success_at TIMESTAMP(3),
    next_poll_at TIMESTAMP(3),
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_http_status INT,
    last_error VARCHAR(2000),
    config_fingerprint VARCHAR(64) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_source_endpoint_key
    ON mate_ai_news_source_endpoint(endpoint_key);
CREATE INDEX IF NOT EXISTS idx_ai_news_source_endpoint_due
    ON mate_ai_news_source_endpoint(enabled, next_poll_at);
CREATE INDEX IF NOT EXISTS idx_ai_news_source_endpoint_url
    ON mate_ai_news_source_endpoint(provider_id, endpoint_url_hash);

CREATE TABLE IF NOT EXISTS mate_ai_news_ingestion_run (
    id BIGINT NOT NULL PRIMARY KEY,
    endpoint_id BIGINT NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64),
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3),
    run_status VARCHAR(32) NOT NULL DEFAULT 'started',
    http_status INT,
    not_modified BOOLEAN NOT NULL DEFAULT FALSE,
    transport_count INT NOT NULL DEFAULT 0,
    item_count INT NOT NULL DEFAULT 0,
    new_item_count INT NOT NULL DEFAULT 0,
    new_version_count INT NOT NULL DEFAULT 0,
    unchanged_item_count INT NOT NULL DEFAULT 0,
    bytes_received BIGINT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_message VARCHAR(2000),
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ai_news_ingestion_run_endpoint
    ON mate_ai_news_ingestion_run(endpoint_id, started_at);
CREATE INDEX IF NOT EXISTS idx_ai_news_ingestion_run_status
    ON mate_ai_news_ingestion_run(run_status, started_at);

CREATE TABLE IF NOT EXISTS mate_ai_news_source_item (
    id BIGINT NOT NULL PRIMARY KEY,
    endpoint_id BIGINT NOT NULL,
    identity_hash VARCHAR(64) NOT NULL,
    external_item_id VARCHAR(1024),
    canonical_url VARCHAR(4096),
    canonical_url_hash VARCHAR(64),
    source_url VARCHAR(4096) NOT NULL,
    source_tier VARCHAR(16) NOT NULL DEFAULT 'community',
    first_observed_at TIMESTAMP(3) NOT NULL,
    last_observed_at TIMESTAMP(3) NOT NULL,
    latest_version_id BIGINT,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_source_item_identity
    ON mate_ai_news_source_item(endpoint_id, identity_hash);
CREATE INDEX IF NOT EXISTS idx_ai_news_source_item_canonical
    ON mate_ai_news_source_item(canonical_url_hash);
CREATE INDEX IF NOT EXISTS idx_ai_news_source_item_seen
    ON mate_ai_news_source_item(endpoint_id, last_observed_at);

CREATE TABLE IF NOT EXISTS mate_ai_news_source_item_version (
    id BIGINT NOT NULL PRIMARY KEY,
    source_item_id BIGINT NOT NULL,
    ingestion_run_id BIGINT NOT NULL,
    version_hash VARCHAR(64) NOT NULL,
    title VARCHAR(512),
    snippet TEXT,
    content TEXT,
    source_published_at TIMESTAMP(3),
    published_at_raw VARCHAR(512),
    source_modified_at TIMESTAMP(3),
    modified_at_raw VARCHAR(512),
    language VARCHAR(32),
    provenance_json TEXT NOT NULL,
    observed_at TIMESTAMP(3) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_source_item_version
    ON mate_ai_news_source_item_version(source_item_id, version_hash);
CREATE INDEX IF NOT EXISTS idx_ai_news_source_item_version_run
    ON mate_ai_news_source_item_version(ingestion_run_id);
CREATE INDEX IF NOT EXISTS idx_ai_news_source_item_version_published
    ON mate_ai_news_source_item_version(source_published_at);

CREATE TABLE IF NOT EXISTS mate_ai_news_ingestion_run_item (
    id BIGINT NOT NULL PRIMARY KEY,
    ingestion_run_id BIGINT NOT NULL,
    source_item_id BIGINT NOT NULL,
    source_item_version_id BIGINT NOT NULL,
    observation_outcome VARCHAR(32) NOT NULL,
    observed_at TIMESTAMP(3) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_ingestion_run_item
    ON mate_ai_news_ingestion_run_item(ingestion_run_id, source_item_id);
CREATE INDEX IF NOT EXISTS idx_ai_news_ingestion_run_item_version
    ON mate_ai_news_ingestion_run_item(source_item_version_id);

CREATE TABLE IF NOT EXISTS mate_ai_news_raw_capture (
    id BIGINT NOT NULL PRIMARY KEY,
    ingestion_run_id BIGINT NOT NULL,
    endpoint_id BIGINT NOT NULL,
    request_url VARCHAR(4096) NOT NULL,
    request_url_hash VARCHAR(64) NOT NULL,
    attempt_no INT NOT NULL DEFAULT 1,
    final_url VARCHAR(4096),
    http_status INT,
    content_type VARCHAR(256),
    etag VARCHAR(1024),
    last_modified VARCHAR(1024),
    retry_after VARCHAR(512),
    declared_content_length BIGINT,
    received_bytes BIGINT NOT NULL DEFAULT 0,
    representation_digest VARCHAR(64),
    retention_applied VARCHAR(32) NOT NULL,
    body_object_key VARCHAR(1024),
    raw_body BYTEA,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    not_modified BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_message VARCHAR(2000),
    revalidated_from_capture_id BIGINT,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_raw_capture_run_url
    ON mate_ai_news_raw_capture(ingestion_run_id, request_url_hash, attempt_no);
CREATE INDEX IF NOT EXISTS idx_ai_news_raw_capture_resource
    ON mate_ai_news_raw_capture(endpoint_id, request_url_hash, finished_at);

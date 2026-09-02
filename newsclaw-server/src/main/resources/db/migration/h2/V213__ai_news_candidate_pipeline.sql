-- Shadow candidate pipeline: durable scan state, queryable candidates and every provider observation.
CREATE TABLE IF NOT EXISTS mate_ai_news_scan_run (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    topic VARCHAR(1000) NOT NULL,
    window_start TIMESTAMP(3) NOT NULL,
    window_end TIMESTAMP(3) NOT NULL,
    run_status VARCHAR(32) NOT NULL,
    config_version VARCHAR(128) NOT NULL,
    discovery_run_id BIGINT,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3),
    provider_count INT NOT NULL DEFAULT 0,
    provider_disabled_count INT NOT NULL DEFAULT 0,
    raw_result_count INT NOT NULL DEFAULT 0,
    invalid_result_count INT NOT NULL DEFAULT 0,
    unique_candidate_count INT NOT NULL DEFAULT 0,
    selected_candidate_count INT NOT NULL DEFAULT 0,
    capture_success_count INT NOT NULL DEFAULT 0,
    capture_failure_count INT NOT NULL DEFAULT 0,
    reviewed_count INT NOT NULL DEFAULT 0,
    accepted_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(2000),
    summary_json CLOB,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ai_news_scan_workspace
    ON mate_ai_news_scan_run(workspace_id, started_at);
CREATE INDEX IF NOT EXISTS idx_ai_news_scan_status
    ON mate_ai_news_scan_run(run_status, started_at);

CREATE TABLE IF NOT EXISTS mate_ai_news_candidate (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    scan_run_id BIGINT NOT NULL,
    canonical_url VARCHAR(4096) NOT NULL,
    canonical_url_hash VARCHAR(64) NOT NULL,
    original_url VARCHAR(4096) NOT NULL,
    title VARCHAR(512),
    snippet CLOB,
    provider_id VARCHAR(64) NOT NULL,
    query_lane VARCHAR(128) NOT NULL,
    provider_rank INT NOT NULL,
    source_key VARCHAR(128),
    source_class VARCHAR(32) NOT NULL,
    published_at_hint VARCHAR(512),
    time_confidence VARCHAR(32) NOT NULL,
    first_seen_at TIMESTAMP(3) NOT NULL,
    last_seen_at TIMESTAMP(3) NOT NULL,
    acquisition_status VARCHAR(32) NOT NULL,
    selection_status VARCHAR(32) NOT NULL,
    capture_status VARCHAR(32) NOT NULL,
    normalization_status VARCHAR(32) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    selection_score DECIMAL(18,8),
    selection_reason VARCHAR(512),
    capture_id BIGINT,
    capture_attempts INT NOT NULL DEFAULT 0,
    capture_started_at TIMESTAMP(3),
    next_capture_at TIMESTAMP(3),
    story_id BIGINT,
    reject_reason VARCHAR(512),
    failure_reason VARCHAR(2000),
    review_reason VARCHAR(1000),
    config_version VARCHAR(128) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_candidate_url
    ON mate_ai_news_candidate(workspace_id, canonical_url_hash);
CREATE INDEX IF NOT EXISTS idx_ai_news_candidate_scan
    ON mate_ai_news_candidate(scan_run_id, selection_status);
CREATE INDEX IF NOT EXISTS idx_ai_news_candidate_queue
    ON mate_ai_news_candidate(capture_status, next_capture_at);
CREATE INDEX IF NOT EXISTS idx_ai_news_candidate_review
    ON mate_ai_news_candidate(workspace_id, review_status, last_seen_at);

CREATE TABLE IF NOT EXISTS mate_ai_news_candidate_observation (
    id BIGINT NOT NULL PRIMARY KEY,
    candidate_id BIGINT NOT NULL,
    scan_run_id BIGINT NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    query_lane VARCHAR(128) NOT NULL,
    provider_rank INT NOT NULL,
    original_url VARCHAR(4096) NOT NULL,
    observed_url_hash VARCHAR(64) NOT NULL,
    title VARCHAR(512),
    snippet CLOB,
    published_at_hint VARCHAR(512),
    provider_score DECIMAL(18,8),
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    selection_reason VARCHAR(512),
    observed_at TIMESTAMP(3) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_candidate_observation
    ON mate_ai_news_candidate_observation(
        scan_run_id, provider_id, query_lane, provider_rank, observed_url_hash);
CREATE INDEX IF NOT EXISTS idx_ai_news_observation_candidate
    ON mate_ai_news_candidate_observation(candidate_id, observed_at);
CREATE INDEX IF NOT EXISTS idx_ai_news_observation_provider
    ON mate_ai_news_candidate_observation(scan_run_id, provider_id, candidate_id);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon,
                      enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000647, 'ai_news_pipeline', 'AI 新闻候选流水线',
        '后端拥有的候选扫描、查询和审核入口；无需 Agent 复制抓取 ID 或推进持久化状态。',
        'builtin', 'aiNewsCandidateTool', 'pi:inbox', TRUE, TRUE, NOW(), NOW(), 0);

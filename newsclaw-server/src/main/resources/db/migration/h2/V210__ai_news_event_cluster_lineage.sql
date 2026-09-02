-- Versioned, replayable event clustering. Event rows remain immutable business
-- observations; cluster versions preserve every automatic/manual grouping decision.
CREATE TABLE IF NOT EXISTS mate_ai_news_event_cluster (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    cluster_key VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    current_version_id BIGINT,
    created_origin VARCHAR(32) NOT NULL DEFAULT 'ONLINE_RULES',
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_cluster_key
    ON mate_ai_news_event_cluster(workspace_id, cluster_key);
CREATE INDEX IF NOT EXISTS idx_ai_news_cluster_status
    ON mate_ai_news_event_cluster(workspace_id, status, update_time);

CREATE TABLE IF NOT EXISTS mate_ai_news_event_cluster_version (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    cluster_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    change_type VARCHAR(24) NOT NULL,
    representative_event_id BIGINT NOT NULL,
    canonical_title VARCHAR(512) NOT NULL,
    category VARCHAR(32),
    entities_json CLOB,
    earliest_source_published_at TIMESTAMP(3),
    latest_source_published_at TIMESTAMP(3),
    member_count INT NOT NULL,
    algorithm_name VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    feature_version VARCHAR(64) NOT NULL,
    config_hash VARCHAR(64) NOT NULL,
    change_reason VARCHAR(1000),
    created_by VARCHAR(256),
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_cluster_version
    ON mate_ai_news_event_cluster_version(cluster_id, version_no);
CREATE INDEX IF NOT EXISTS idx_ai_news_cluster_version_workspace
    ON mate_ai_news_event_cluster_version(workspace_id, create_time);

CREATE TABLE IF NOT EXISTS mate_ai_news_event_cluster_member (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    cluster_id BIGINT NOT NULL,
    cluster_version_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    membership_score DOUBLE NOT NULL,
    assignment_origin VARCHAR(32) NOT NULL,
    score_breakdown_json CLOB,
    assigned_at TIMESTAMP(3) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_cluster_version_member
    ON mate_ai_news_event_cluster_member(cluster_version_id, event_id);
CREATE INDEX IF NOT EXISTS idx_ai_news_cluster_member_event
    ON mate_ai_news_event_cluster_member(workspace_id, event_id, cluster_version_id);
CREATE INDEX IF NOT EXISTS idx_ai_news_cluster_member_version
    ON mate_ai_news_event_cluster_member(cluster_id, cluster_version_id);

CREATE TABLE IF NOT EXISTS mate_ai_news_event_cluster_lineage (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    operation_id VARCHAR(64) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    from_cluster_id BIGINT NOT NULL,
    from_version_id BIGINT NOT NULL,
    to_cluster_id BIGINT NOT NULL,
    to_version_id BIGINT NOT NULL,
    reason VARCHAR(1000),
    reviewer VARCHAR(256),
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ai_news_cluster_lineage_operation
    ON mate_ai_news_event_cluster_lineage(workspace_id, operation_id);
CREATE INDEX IF NOT EXISTS idx_ai_news_cluster_lineage_from
    ON mate_ai_news_event_cluster_lineage(from_cluster_id, from_version_id);
CREATE INDEX IF NOT EXISTS idx_ai_news_cluster_lineage_to
    ON mate_ai_news_event_cluster_lineage(to_cluster_id, to_version_id);

CREATE TABLE IF NOT EXISTS mate_ai_news_event_cluster_review (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    source_cluster_id BIGINT NOT NULL,
    candidate_cluster_id BIGINT NOT NULL,
    proposed_action VARCHAR(16) NOT NULL DEFAULT 'MERGE',
    score DOUBLE NOT NULL,
    decision_threshold DOUBLE NOT NULL,
    algorithm_name VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    feature_version VARCHAR(64) NOT NULL,
    config_hash VARCHAR(64) NOT NULL,
    score_breakdown_json CLOB,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reviewer VARCHAR(256),
    review_note VARCHAR(1000),
    resolved_at TIMESTAMP(3),
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ai_news_cluster_review_status
    ON mate_ai_news_event_cluster_review(workspace_id, status, create_time);
CREATE INDEX IF NOT EXISTS idx_ai_news_cluster_review_event
    ON mate_ai_news_event_cluster_review(workspace_id, event_id, candidate_cluster_id);

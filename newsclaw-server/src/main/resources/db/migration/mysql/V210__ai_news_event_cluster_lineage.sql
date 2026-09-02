-- Versioned, replayable event clustering. Event rows remain immutable business
-- observations; cluster versions preserve every automatic/manual grouping decision.
CREATE TABLE IF NOT EXISTS mate_ai_news_event_cluster (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    cluster_key VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    current_version_id BIGINT,
    created_origin VARCHAR(32) NOT NULL DEFAULT 'ONLINE_RULES',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_news_cluster_key (workspace_id, cluster_key),
    KEY idx_ai_news_cluster_status (workspace_id, status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mate_ai_news_event_cluster_version (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    cluster_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    change_type VARCHAR(24) NOT NULL,
    representative_event_id BIGINT NOT NULL,
    canonical_title VARCHAR(512) NOT NULL,
    category VARCHAR(32),
    entities_json LONGTEXT,
    earliest_source_published_at DATETIME(3),
    latest_source_published_at DATETIME(3),
    member_count INT NOT NULL,
    algorithm_name VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    feature_version VARCHAR(64) NOT NULL,
    config_hash VARCHAR(64) NOT NULL,
    change_reason VARCHAR(1000),
    created_by VARCHAR(256),
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_news_cluster_version (cluster_id, version_no),
    KEY idx_ai_news_cluster_version_workspace (workspace_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mate_ai_news_event_cluster_member (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    cluster_id BIGINT NOT NULL,
    cluster_version_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    membership_score DOUBLE NOT NULL,
    assignment_origin VARCHAR(32) NOT NULL,
    score_breakdown_json LONGTEXT,
    assigned_at DATETIME(3) NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_news_cluster_version_member (cluster_version_id, event_id),
    KEY idx_ai_news_cluster_member_event (workspace_id, event_id, cluster_version_id),
    KEY idx_ai_news_cluster_member_version (cluster_id, cluster_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted INT NOT NULL DEFAULT 0,
    KEY idx_ai_news_cluster_lineage_operation (workspace_id, operation_id),
    KEY idx_ai_news_cluster_lineage_from (from_cluster_id, from_version_id),
    KEY idx_ai_news_cluster_lineage_to (to_cluster_id, to_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
    score_breakdown_json LONGTEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reviewer VARCHAR(256),
    review_note VARCHAR(1000),
    resolved_at DATETIME(3),
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted INT NOT NULL DEFAULT 0,
    KEY idx_ai_news_cluster_review_status (workspace_id, status, create_time),
    KEY idx_ai_news_cluster_review_event (workspace_id, event_id, candidate_cluster_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

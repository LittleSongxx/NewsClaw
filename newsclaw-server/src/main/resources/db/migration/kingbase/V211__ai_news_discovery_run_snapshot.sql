-- Content-addressed, replayable snapshots for every AI-news discovery run.
CREATE TABLE IF NOT EXISTS mate_ai_news_discovery_run (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    topic VARCHAR(1000) NOT NULL,
    window_start TIMESTAMP(3) NOT NULL,
    window_end TIMESTAMP(3) NOT NULL,
    observed_at TIMESTAMP(3) NOT NULL,
    requested_max_candidates INT NOT NULL,
    query_count INT NOT NULL,
    cached_query_count INT NOT NULL DEFAULT 0,
    successful_query_count INT NOT NULL DEFAULT 0,
    unique_url_count INT NOT NULL DEFAULT 0,
    selected_candidate_count INT NOT NULL DEFAULT 0,
    structured_source_count INT NOT NULL DEFAULT 0,
    ranking_policy_version VARCHAR(128) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    ranking_hash VARCHAR(64) NOT NULL,
    snapshot_json TEXT NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ai_news_discovery_run_workspace
    ON mate_ai_news_discovery_run(workspace_id, observed_at);
CREATE INDEX IF NOT EXISTS idx_ai_news_discovery_run_window
    ON mate_ai_news_discovery_run(window_start, window_end, ranking_policy_version);
CREATE INDEX IF NOT EXISTS idx_ai_news_discovery_run_snapshot
    ON mate_ai_news_discovery_run(snapshot_hash);

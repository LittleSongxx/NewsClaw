CREATE TABLE IF NOT EXISTS mate_ai_news_feedback (
    id              BIGINT       NOT NULL PRIMARY KEY,
    workspace_id    BIGINT       NOT NULL,
    feedback_hash   VARCHAR(64)  NOT NULL,
    event_id        BIGINT,
    team_run_id     BIGINT,
    task_id         BIGINT,
    feedback_type   VARCHAR(64)  NOT NULL,
    note            TEXT         NOT NULL,
    evidence_json   TEXT,
    skill_name      VARCHAR(128),
    proposal_action VARCHAR(16),
    proposal_id     BIGINT,
    status          VARCHAR(32)  NOT NULL DEFAULT 'RECORDED',
    create_time     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_feedback_hash
    ON mate_ai_news_feedback (workspace_id, feedback_hash, deleted);
CREATE INDEX IF NOT EXISTS idx_ai_news_feedback_event
    ON mate_ai_news_feedback (workspace_id, event_id, create_time);
CREATE INDEX IF NOT EXISTS idx_ai_news_feedback_status
    ON mate_ai_news_feedback (workspace_id, status, create_time);

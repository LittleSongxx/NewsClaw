-- V174: Team task event timeline — an append-only audit trail of task lifecycle
-- moments. (KingbaseES / PostgreSQL dialect). See h2/V174 for design notes.

CREATE TABLE IF NOT EXISTS mate_team_task_event (
    id           BIGINT        NOT NULL PRIMARY KEY,
    team_id      BIGINT        NOT NULL,
    task_id      BIGINT        NOT NULL,
    event_type   VARCHAR(32)   NOT NULL,
    actor_type   VARCHAR(16),
    actor_id     VARCHAR(64),
    detail       VARCHAR(1000),
    create_time  TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      INT           DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_team_task_event_task ON mate_team_task_event(task_id);
CREATE INDEX IF NOT EXISTS idx_team_task_event_team ON mate_team_task_event(team_id, create_time);

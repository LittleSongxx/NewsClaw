-- Durable deterministic human-review queue. A channel card is only a notification transport.
CREATE TABLE IF NOT EXISTS mate_ai_news_review_task (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    workspace_id        BIGINT       NOT NULL,
    event_id            BIGINT       NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    reasons_json        TEXT,
    policy_version      VARCHAR(128) NOT NULL,
    risk_fingerprint    VARCHAR(64)  NOT NULL,
    route_source        VARCHAR(32)  NOT NULL DEFAULT 'DETERMINISTIC_POLICY',
    card_issued_at      TIMESTAMP(3),
    card_delivery_error TEXT,
    resolved_at         TIMESTAMP(3),
    resolved_by         VARCHAR(256),
    resolution_note     TEXT,
    create_time         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_review_task_event
    ON mate_ai_news_review_task (workspace_id, event_id, deleted);
CREATE INDEX IF NOT EXISTS idx_ai_news_review_task_status
    ON mate_ai_news_review_task (workspace_id, status, update_time);

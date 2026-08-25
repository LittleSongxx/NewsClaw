-- Durable deterministic human-review queue. A channel card is only a notification transport.
CREATE TABLE IF NOT EXISTS mate_ai_news_review_task (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    workspace_id        BIGINT       NOT NULL,
    event_id            BIGINT       NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    reasons_json        MEDIUMTEXT,
    policy_version      VARCHAR(128) NOT NULL,
    risk_fingerprint    VARCHAR(64)  NOT NULL,
    route_source        VARCHAR(32)  NOT NULL DEFAULT 'DETERMINISTIC_POLICY',
    card_issued_at      DATETIME,
    card_delivery_error TEXT,
    resolved_at         DATETIME,
    resolved_by         VARCHAR(256),
    resolution_note     TEXT,
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_news_review_task_event (workspace_id, event_id, deleted),
    KEY idx_ai_news_review_task_status (workspace_id, status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Deterministic AI news human review queue';

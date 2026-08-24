-- Durable observability for model routing and external side effects.

CREATE TABLE IF NOT EXISTS mate_llm_routing_trace (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    workspace_id       BIGINT,
    agent_id           BIGINT,
    conversation_id    VARCHAR(256),
    phase              VARCHAR(128),
    route_role         VARCHAR(32)  NOT NULL,
    provider_id        VARCHAR(128),
    model_name         VARCHAR(256),
    attempt_no         INT          NOT NULL DEFAULT 0,
    fallback_ordinal   INT          NOT NULL DEFAULT 0,
    outcome            VARCHAR(32)  NOT NULL,
    failure_category   VARCHAR(64),
    duration_ms        BIGINT       NOT NULL DEFAULT 0,
    metadata_json      TEXT,
    create_time        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_llm_trace_workspace_time
    ON mate_llm_routing_trace (workspace_id, create_time);
CREATE INDEX IF NOT EXISTS idx_llm_trace_conversation_time
    ON mate_llm_routing_trace (conversation_id, create_time);

CREATE TABLE IF NOT EXISTS mate_external_effect (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    workspace_id       BIGINT       NOT NULL,
    effect_type        VARCHAR(64)  NOT NULL,
    idempotency_key    VARCHAR(191) NOT NULL,
    aggregate_type     VARCHAR(64),
    aggregate_id       VARCHAR(128),
    target             VARCHAR(512),
    request_digest     VARCHAR(64),
    request_json       TEXT,
    status             VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    response_json      TEXT,
    error_message      VARCHAR(1000),
    attempt_count      INT          NOT NULL DEFAULT 1,
    owner_token        VARCHAR(64),
    started_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at        TIMESTAMP(3),
    create_time        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_external_effect_idempotency
    ON mate_external_effect (workspace_id, effect_type, idempotency_key, deleted);
CREATE INDEX IF NOT EXISTS idx_external_effect_status_time
    ON mate_external_effect (status, started_at);

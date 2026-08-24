CREATE TABLE IF NOT EXISTS mate_memory_write_ledger (
    id                      BIGINT       NOT NULL PRIMARY KEY,
    workspace_id            BIGINT       NOT NULL,
    agent_id                BIGINT       NOT NULL,
    owner_key               VARCHAR(128),
    memory_type             VARCHAR(32)  NOT NULL,
    memory_key              VARCHAR(256) NOT NULL,
    source                  VARCHAR(64)  NOT NULL,
    source_conversation_id  VARCHAR(256),
    source_ref              VARCHAR(1000),
    content_hash            VARCHAR(64)  NOT NULL,
    content                 CLOB,
    token_estimate          INT          NOT NULL DEFAULT 0,
    version_no              INT          NOT NULL DEFAULT 1,
    supersedes_id           BIGINT,
    status                  VARCHAR(32)  NOT NULL,
    rejection_reason        VARCHAR(1000),
    resolved_by             VARCHAR(128),
    resolved_at             TIMESTAMP,
    create_time             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                 INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_memory_ledger_scope_key
    ON mate_memory_write_ledger (workspace_id, agent_id, owner_key, memory_type, memory_key, create_time);
CREATE INDEX IF NOT EXISTS idx_memory_ledger_status_time
    ON mate_memory_write_ledger (workspace_id, status, create_time);

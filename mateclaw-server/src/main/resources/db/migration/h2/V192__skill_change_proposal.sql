CREATE TABLE IF NOT EXISTS mate_skill_change_proposal (
    id                      BIGINT       NOT NULL PRIMARY KEY,
    workspace_id            BIGINT       NOT NULL,
    proposal_hash           VARCHAR(64)  NOT NULL,
    agent_id                BIGINT,
    source_type             VARCHAR(32)  NOT NULL,
    source_conversation_id  VARCHAR(256),
    source_run_id           BIGINT,
    action                  VARCHAR(16)  NOT NULL,
    skill_name              VARCHAR(128) NOT NULL,
    before_content          CLOB,
    after_content           CLOB,
    diff_text               CLOB,
    evidence_json           CLOB,
    risk_level              VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    status                  VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    reviewer                VARCHAR(128),
    review_note             VARCHAR(1000),
    snapshot_id             BIGINT,
    applied_skill_id        BIGINT,
    applied_version         VARCHAR(64),
    rollback_status         VARCHAR(24),
    reviewed_at             TIMESTAMP,
    applied_at              TIMESTAMP,
    rolled_back_at          TIMESTAMP,
    create_time             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                 INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_proposal_hash
    ON mate_skill_change_proposal (workspace_id, proposal_hash, deleted);
CREATE INDEX IF NOT EXISTS idx_skill_proposal_status_time
    ON mate_skill_change_proposal (workspace_id, status, create_time);

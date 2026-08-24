-- V2: Upgrade schema for databases created before Flyway was introduced.
-- All statements use IF NOT EXISTS / IF EXISTS to be idempotent.

-- ===== Workspace tables =====
CREATE TABLE IF NOT EXISTS mate_workspace (
    id            BIGINT       NOT NULL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    slug          VARCHAR(64)  NOT NULL,
    description   VARCHAR(256),
    owner_id      BIGINT,
    settings_json TEXT,
    base_path     VARCHAR(512),
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_workspace_slug UNIQUE (slug)
);
ALTER TABLE mate_workspace ADD COLUMN IF NOT EXISTS base_path VARCHAR(512);

CREATE TABLE IF NOT EXISTS mate_workspace_member (
    id           BIGINT      NOT NULL PRIMARY KEY,
    workspace_id BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(32) NOT NULL DEFAULT 'member',
    create_time  DATETIME    NOT NULL,
    update_time  DATETIME    NOT NULL,
    deleted      INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ws_member_workspace ON mate_workspace_member(workspace_id);
CREATE INDEX IF NOT EXISTS idx_ws_member_user ON mate_workspace_member(user_id);

-- ===== Add workspace_id column to tables that may lack it (pre-workspace era databases) =====
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE mate_channel ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE mate_wiki_knowledge_base ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE mate_tool ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;

-- ===== Tables that may not exist in very old databases =====
CREATE TABLE IF NOT EXISTS mate_workspace_file (
    id              BIGINT       NOT NULL PRIMARY KEY,
    agent_id        BIGINT       NOT NULL,
    filename        VARCHAR(256) NOT NULL,
    content         CLOB,
    file_size       BIGINT       NOT NULL DEFAULT 0,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_workspace_file_agent ON mate_workspace_file(agent_id);

CREATE TABLE IF NOT EXISTS mate_usage_daily (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    workspace_id       BIGINT       NOT NULL,
    agent_id           BIGINT       NOT NULL,
    stat_date          DATE         NOT NULL,
    conversation_count INT          NOT NULL DEFAULT 0,
    message_count      INT          NOT NULL DEFAULT 0,
    tool_call_count    INT          NOT NULL DEFAULT 0,
    prompt_tokens      BIGINT       NOT NULL DEFAULT 0,
    completion_tokens  BIGINT       NOT NULL DEFAULT 0,
    create_time        DATETIME     NOT NULL,
    update_time        DATETIME     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_usage_daily ON mate_usage_daily(workspace_id, agent_id, stat_date);

CREATE TABLE IF NOT EXISTS mate_audit_event (
    id             BIGINT       NOT NULL PRIMARY KEY,
    workspace_id   BIGINT,
    user_id        BIGINT,
    username       VARCHAR(64),
    action         VARCHAR(64)  NOT NULL,
    resource_type  VARCHAR(64),
    resource_id    VARCHAR(128),
    detail         TEXT,
    ip_address     VARCHAR(64),
    create_time    DATETIME     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_ws_time ON mate_audit_event(workspace_id, create_time);

-- ===== OAuth columns on model_provider =====
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS auth_type VARCHAR(16) NOT NULL DEFAULT 'api_key';
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS oauth_access_token TEXT;
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS oauth_refresh_token TEXT;
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS oauth_expires_at BIGINT;
ALTER TABLE mate_model_provider ADD COLUMN IF NOT EXISTS oauth_account_id VARCHAR(128);

-- ===== Agent-Skill / Agent-Tool binding tables =====
CREATE TABLE IF NOT EXISTS mate_agent_skill (
    id           BIGINT    NOT NULL PRIMARY KEY,
    agent_id     BIGINT    NOT NULL,
    skill_id     BIGINT    NOT NULL,
    create_time  DATETIME  NOT NULL,
    deleted      INT       NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS mate_agent_tool (
    id           BIGINT    NOT NULL PRIMARY KEY,
    agent_id     BIGINT    NOT NULL,
    tool_name    VARCHAR(128) NOT NULL,
    create_time  DATETIME  NOT NULL,
    deleted      INT       NOT NULL DEFAULT 0
);

-- ===== Memory recall table =====
CREATE TABLE IF NOT EXISTS mate_memory_recall (
    id                BIGINT       NOT NULL PRIMARY KEY,
    agent_id          BIGINT       NOT NULL,
    filename          VARCHAR(256) NOT NULL,
    content           CLOB,
    tags              VARCHAR(512),
    score             DOUBLE       NOT NULL DEFAULT 0.0,
    last_recalled_at  DATETIME,
    promoted          BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time       DATETIME     NOT NULL,
    update_time       DATETIME     NOT NULL,
    deleted           INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_memory_recall_agent ON mate_memory_recall(agent_id);

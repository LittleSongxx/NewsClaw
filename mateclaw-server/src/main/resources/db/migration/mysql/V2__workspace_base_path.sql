-- V2: Upgrade schema for databases created before Flyway was introduced.
-- MySQL does NOT support `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` (MariaDB-only).
-- We use INFORMATION_SCHEMA + dynamic SQL as an idempotent replacement so this migration
-- is safe on BOTH: (a) fresh MySQL installs whose V1 baseline already contains the columns,
-- and (b) legacy installs bootstrapped from the old schema.sql that predates those columns.

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
    UNIQUE KEY uk_workspace_slug (slug)
);

-- mate_workspace.base_path (legacy upgrade path)
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_workspace' AND COLUMN_NAME = 'base_path');
SET @s := IF(@c = 0, 'ALTER TABLE mate_workspace ADD COLUMN base_path VARCHAR(512)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS mate_workspace_member (
    id           BIGINT      NOT NULL PRIMARY KEY,
    workspace_id BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(32) NOT NULL DEFAULT 'member',
    create_time  DATETIME    NOT NULL,
    update_time  DATETIME    NOT NULL,
    deleted      INT         NOT NULL DEFAULT 0,
    INDEX idx_ws_member_workspace (workspace_id),
    INDEX idx_ws_member_user (user_id)
);

-- workspace_id on pre-existing domain tables
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_agent' AND COLUMN_NAME = 'workspace_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_agent ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_channel' AND COLUMN_NAME = 'workspace_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_channel ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_conversation' AND COLUMN_NAME = 'workspace_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_conversation ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_knowledge_base' AND COLUMN_NAME = 'workspace_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_knowledge_base ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_tool' AND COLUMN_NAME = 'workspace_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_tool ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_skill' AND COLUMN_NAME = 'workspace_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_skill ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS mate_workspace_file (
    id              BIGINT       NOT NULL PRIMARY KEY,
    agent_id        BIGINT       NOT NULL,
    filename        VARCHAR(256) NOT NULL,
    content         LONGTEXT,
    file_size       BIGINT       NOT NULL DEFAULT 0,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0,
    INDEX idx_workspace_file_agent (agent_id)
);

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
    update_time        DATETIME     NOT NULL,
    UNIQUE KEY uk_usage_daily (workspace_id, agent_id, stat_date)
);

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
    create_time    DATETIME     NOT NULL,
    INDEX idx_audit_ws_time (workspace_id, create_time)
);

-- model provider OAuth columns
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_model_provider' AND COLUMN_NAME = 'auth_type');
SET @s := IF(@c = 0, 'ALTER TABLE mate_model_provider ADD COLUMN auth_type VARCHAR(16) NOT NULL DEFAULT ''api_key''', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_model_provider' AND COLUMN_NAME = 'oauth_access_token');
SET @s := IF(@c = 0, 'ALTER TABLE mate_model_provider ADD COLUMN oauth_access_token TEXT', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_model_provider' AND COLUMN_NAME = 'oauth_refresh_token');
SET @s := IF(@c = 0, 'ALTER TABLE mate_model_provider ADD COLUMN oauth_refresh_token TEXT', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_model_provider' AND COLUMN_NAME = 'oauth_expires_at');
SET @s := IF(@c = 0, 'ALTER TABLE mate_model_provider ADD COLUMN oauth_expires_at BIGINT', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_model_provider' AND COLUMN_NAME = 'oauth_account_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_model_provider ADD COLUMN oauth_account_id VARCHAR(128)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

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

CREATE TABLE IF NOT EXISTS mate_memory_recall (
    id                BIGINT       NOT NULL PRIMARY KEY,
    agent_id          BIGINT       NOT NULL,
    filename          VARCHAR(256) NOT NULL,
    content           LONGTEXT,
    tags              VARCHAR(512),
    score             DOUBLE       NOT NULL DEFAULT 0.0,
    last_recalled_at  DATETIME,
    promoted          BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time       DATETIME     NOT NULL,
    update_time       DATETIME     NOT NULL,
    deleted           INT          NOT NULL DEFAULT 0,
    INDEX idx_memory_recall_agent (agent_id)
);

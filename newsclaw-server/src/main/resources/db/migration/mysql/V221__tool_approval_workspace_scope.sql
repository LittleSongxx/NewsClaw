-- Persist the workspace that owns an approval so admin activity queries do not
-- have to trust the client-supplied conversation id or parse JSON snapshots.
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_tool_approval' AND COLUMN_NAME = 'workspace_id');
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_tool_approval ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_tool_approval'
      AND INDEX_NAME = 'idx_tool_approval_workspace_time');
SET @stmt := IF(@idx_exists = 0,
    'CREATE INDEX idx_tool_approval_workspace_time ON mate_tool_approval(workspace_id, created_at)',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

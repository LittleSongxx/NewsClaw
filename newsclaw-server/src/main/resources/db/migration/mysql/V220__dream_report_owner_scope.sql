SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_dream_report' AND COLUMN_NAME = 'owner_key');
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_dream_report ADD COLUMN owner_key VARCHAR(128) NULL',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_dream_report' AND INDEX_NAME = 'idx_dream_agent_owner_time');
SET @stmt := IF(@idx_exists = 0,
    'CREATE INDEX idx_dream_agent_owner_time ON mate_dream_report(agent_id, owner_key, started_at)',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

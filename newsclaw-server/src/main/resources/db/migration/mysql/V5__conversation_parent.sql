-- V5: Add parent_conversation_id to mate_conversation for multi-agent delegation tracking
-- MySQL lacks `ADD COLUMN IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`; use INFORMATION_SCHEMA guards.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_conversation' AND COLUMN_NAME = 'parent_conversation_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_conversation ADD COLUMN parent_conversation_id VARCHAR(64) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_conversation' AND INDEX_NAME = 'idx_conversation_parent');
SET @s := IF(@c = 0, 'CREATE INDEX idx_conversation_parent ON mate_conversation(parent_conversation_id)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V4: Add default_thinking_level to mate_agent
-- Supports: off / low / medium / high / max (null = follow model default)
-- MySQL lacks `ADD COLUMN IF NOT EXISTS`; use INFORMATION_SCHEMA guard instead.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_agent' AND COLUMN_NAME = 'default_thinking_level');
SET @s := IF(@c = 0, 'ALTER TABLE mate_agent ADD COLUMN default_thinking_level VARCHAR(32) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

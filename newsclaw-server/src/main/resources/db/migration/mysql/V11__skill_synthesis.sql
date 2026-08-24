-- V11: Auto Skill Synthesis (RFC-023)
-- Agent 自治创建 skill 后记录来源对话和安全扫描状态
-- MySQL lacks `ADD COLUMN IF NOT EXISTS`; use INFORMATION_SCHEMA guard instead.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_skill' AND COLUMN_NAME = 'source_conversation_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_skill ADD COLUMN source_conversation_id VARCHAR(64) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- security_scan_status: NULL(旧数据/手动创建) / PASSED / FAILED
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_skill' AND COLUMN_NAME = 'security_scan_status');
SET @s := IF(@c = 0, 'ALTER TABLE mate_skill ADD COLUMN security_scan_status VARCHAR(16) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

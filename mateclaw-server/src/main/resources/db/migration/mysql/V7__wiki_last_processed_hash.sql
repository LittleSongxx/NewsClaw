-- V7: Add last_processed_hash to mate_wiki_raw_material for skip-if-unchanged optimization
-- RFC-012 Change 5: when reprocessing, skip LLM pipeline if content_hash == last_processed_hash
-- MySQL lacks `ADD COLUMN IF NOT EXISTS`; use INFORMATION_SCHEMA guard instead.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_raw_material' AND COLUMN_NAME = 'last_processed_hash');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_raw_material ADD COLUMN last_processed_hash VARCHAR(64) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V13: Add embedding column to mate_wiki_chunk (RFC-011 Phase 2)
-- MySQL lacks `ADD COLUMN IF NOT EXISTS`; use INFORMATION_SCHEMA guard instead.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_chunk' AND COLUMN_NAME = 'embedding');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_chunk ADD COLUMN embedding BLOB DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_chunk' AND COLUMN_NAME = 'embedding_model');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_chunk ADD COLUMN embedding_model VARCHAR(64) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

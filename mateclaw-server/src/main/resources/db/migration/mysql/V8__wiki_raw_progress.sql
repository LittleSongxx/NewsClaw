-- V8: wiki raw material two-phase digest progress fields, for UI progress bar
-- RFC-012 M2 v2 UI follow-up: expose per-raw progress (current phase + pages done / total planned)
-- so the frontend can render a determinate progress bar instead of an opaque "处理中" badge.
-- MySQL lacks `ADD COLUMN IF NOT EXISTS`; use INFORMATION_SCHEMA guard instead.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_raw_material' AND COLUMN_NAME = 'progress_phase');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_raw_material ADD COLUMN progress_phase VARCHAR(32) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_raw_material' AND COLUMN_NAME = 'progress_total');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_raw_material ADD COLUMN progress_total INT DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_raw_material' AND COLUMN_NAME = 'progress_done');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_raw_material ADD COLUMN progress_done INT DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

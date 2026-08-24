-- Keep the human-readable canonical URL, but use a fixed-width digest for
-- uniqueness.  A VARCHAR(2048) URL can exceed InnoDB's utf8mb4 index limit.
-- MySQL has no ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS, so use
-- INFORMATION_SCHEMA guards to make repair/replay safe.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_event_evidence'
             AND COLUMN_NAME = 'source_url_hash');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_ai_news_event_evidence ADD COLUMN source_url_hash VARCHAR(64) NULL AFTER source_url',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE mate_ai_news_event_evidence
SET source_url_hash = LOWER(SHA2(source_url, 256))
WHERE source_url_hash IS NULL;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_event_evidence'
             AND INDEX_NAME = 'uk_ai_news_evidence_source');
SET @s := IF(@i > 0,
    'DROP INDEX uk_ai_news_evidence_source ON mate_ai_news_event_evidence',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := 'ALTER TABLE mate_ai_news_event_evidence MODIFY COLUMN source_url_hash VARCHAR(64) NOT NULL';
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_event_evidence'
             AND INDEX_NAME = 'uk_ai_news_evidence_source_hash');
SET @s := IF(@i = 0,
    'CREATE UNIQUE INDEX uk_ai_news_evidence_source_hash ON mate_ai_news_event_evidence(event_id, source_url_hash)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

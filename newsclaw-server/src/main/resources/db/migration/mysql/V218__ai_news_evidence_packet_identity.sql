-- URL is a source identity, not an evidence-packet identity.  Legacy rows are
-- intentionally left NULL and are lazily assigned a packet hash by Java; no
-- SHA2/backfill is needed during a potentially large production migration.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_event_evidence'
             AND COLUMN_NAME = 'evidence_identity_hash');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_ai_news_event_evidence ADD COLUMN evidence_identity_hash VARCHAR(64) NULL AFTER source_url_hash',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_event_evidence'
             AND INDEX_NAME = 'uk_ai_news_evidence_identity');
SET @s := IF(@i = 0,
    'CREATE UNIQUE INDEX uk_ai_news_evidence_identity ON mate_ai_news_event_evidence(event_id, evidence_identity_hash)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Create the replacement before dropping the old constraint.  MySQL DDL
-- implicitly commits, so a failed CREATE must leave the legacy uniqueness
-- protection intact rather than leaving the table half-migrated.
SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_event_evidence'
             AND INDEX_NAME = 'uk_ai_news_evidence_source');
SET @s := IF(@i > 0,
    'DROP INDEX uk_ai_news_evidence_source ON mate_ai_news_event_evidence',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_event_evidence'
             AND INDEX_NAME = 'uk_ai_news_evidence_source_hash');
SET @s := IF(@i > 0,
    'DROP INDEX uk_ai_news_evidence_source_hash ON mate_ai_news_event_evidence',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Explicit, auditable bridge from an accepted candidate to one event.
SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_candidate'
             AND COLUMN_NAME = 'event_id');
SET @s := IF(@i = 0,
    'ALTER TABLE mate_ai_news_candidate ADD COLUMN event_id BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_candidate'
             AND COLUMN_NAME = 'promoted_at');
SET @s := IF(@i = 0,
    'ALTER TABLE mate_ai_news_candidate ADD COLUMN promoted_at DATETIME(3) NULL',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_candidate'
             AND INDEX_NAME = 'idx_ai_news_candidate_event');
SET @s := IF(@i = 0,
    'CREATE INDEX idx_ai_news_candidate_event ON mate_ai_news_candidate(workspace_id, event_id)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

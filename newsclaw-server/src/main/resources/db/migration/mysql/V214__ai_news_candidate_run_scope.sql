-- Candidate state is a decision made by one scan, not a mutable workspace
-- projection.  Keep URL uniqueness inside the scan so a later run cannot
-- overwrite an earlier run's selection, capture or review state.
-- MySQL has no DROP INDEX IF EXISTS / CREATE INDEX IF NOT EXISTS, so guard
-- both operations through INFORMATION_SCHEMA for replay-safe upgrades.
SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_candidate'
             AND INDEX_NAME = 'uk_ai_news_candidate_url');
SET @s := IF(@i > 0,
    'DROP INDEX uk_ai_news_candidate_url ON mate_ai_news_candidate',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_ai_news_candidate'
             AND INDEX_NAME = 'uk_ai_news_candidate_run_url');
SET @s := IF(@i = 0,
    'CREATE UNIQUE INDEX uk_ai_news_candidate_run_url ON mate_ai_news_candidate(workspace_id, scan_run_id, canonical_url_hash)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Keep the human adoption gate distinguishable from an Agent/system label.
SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'mate_ai_news_candidate' AND COLUMN_NAME = 'reviewed_by');
SET @s := IF(@i = 0, 'ALTER TABLE mate_ai_news_candidate ADD COLUMN reviewed_by VARCHAR(256) NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'mate_ai_news_candidate' AND COLUMN_NAME = 'reviewed_at');
SET @s := IF(@i = 0, 'ALTER TABLE mate_ai_news_candidate ADD COLUMN reviewed_at DATETIME(3) NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = 'mate_ai_news_candidate' AND COLUMN_NAME = 'review_origin');
SET @s := IF(@i = 0, 'ALTER TABLE mate_ai_news_candidate ADD COLUMN review_origin VARCHAR(32) NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

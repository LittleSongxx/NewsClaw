SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job_run' AND COLUMN_NAME = 'idempotency_key');
SET @s := IF(@c = 0, 'ALTER TABLE mate_cron_job_run ADD COLUMN idempotency_key VARCHAR(191) NULL', 'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_cron_job_run' AND INDEX_NAME = 'uk_cron_run_idempotency');
SET @s := IF(@c = 0, 'CREATE UNIQUE INDEX uk_cron_run_idempotency ON mate_cron_job_run (cron_job_id, idempotency_key)', 'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

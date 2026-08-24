ALTER TABLE mate_cron_job_run ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(191);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cron_run_idempotency
    ON mate_cron_job_run (cron_job_id, idempotency_key);

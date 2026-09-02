ALTER TABLE mate_dream_report ADD COLUMN IF NOT EXISTS owner_key VARCHAR(128);
CREATE INDEX IF NOT EXISTS idx_dream_agent_owner_time
    ON mate_dream_report(agent_id, owner_key, started_at DESC);

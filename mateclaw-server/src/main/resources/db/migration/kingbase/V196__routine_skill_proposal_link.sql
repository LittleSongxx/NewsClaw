ALTER TABLE mate_skill_routine_candidate ADD COLUMN IF NOT EXISTS proposal_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_skill_routine_proposal ON mate_skill_routine_candidate (proposal_id);

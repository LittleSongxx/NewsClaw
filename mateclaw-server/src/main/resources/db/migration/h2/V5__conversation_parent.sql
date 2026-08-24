-- V5: Add parent_conversation_id to mate_conversation for multi-agent delegation tracking
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS parent_conversation_id VARCHAR(64) DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_conversation_parent ON mate_conversation(parent_conversation_id);

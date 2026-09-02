-- Persist the workspace that owns an approval so admin activity queries do not
-- have to trust the client-supplied conversation id or parse JSON snapshots.
ALTER TABLE mate_tool_approval ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_tool_approval_workspace_time
    ON mate_tool_approval(workspace_id, created_at DESC);

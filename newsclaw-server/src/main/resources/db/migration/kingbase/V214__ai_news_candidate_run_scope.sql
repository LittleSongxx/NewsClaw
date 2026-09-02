-- Candidate state is a decision made by one scan, not a mutable workspace
-- projection.  Keep URL uniqueness inside the scan so a later run cannot
-- overwrite an earlier run's selection, capture or review state.
DROP INDEX IF EXISTS uk_ai_news_candidate_url;
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_candidate_run_url
    ON mate_ai_news_candidate(workspace_id, scan_run_id, canonical_url_hash);

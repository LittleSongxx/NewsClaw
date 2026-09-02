-- URL is a source identity, not an evidence-packet identity.  One article may
-- support several atomic claims/quote spans.  Keep legacy rows nullable and
-- let the application lazily assign their packet hash; this avoids a
-- dialect-specific digest backfill during startup.
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS evidence_identity_hash VARCHAR(64);

-- Create the replacement before dropping the legacy constraint.  If index
-- creation fails, the URL uniqueness guard remains in place for a retry.
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_evidence_identity
    ON mate_ai_news_event_evidence(event_id, evidence_identity_hash);
DROP INDEX IF EXISTS uk_ai_news_evidence_source;

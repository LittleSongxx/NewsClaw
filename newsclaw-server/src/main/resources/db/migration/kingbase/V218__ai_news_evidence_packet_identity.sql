-- URL is a source identity, not an evidence-packet identity.  Legacy rows are
-- intentionally left nullable and are lazily assigned a packet hash by the
-- application, avoiding a pgcrypto/kbcrypto dependency during migration.
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS evidence_identity_hash VARCHAR(64);

-- Create the replacement before dropping legacy constraints.  If index
-- creation fails, the old URL uniqueness guards remain available for retry.
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_news_evidence_identity
    ON mate_ai_news_event_evidence(event_id, evidence_identity_hash);
DROP INDEX IF EXISTS uk_ai_news_evidence_source;
DROP INDEX IF EXISTS uk_ai_news_evidence_source_hash;

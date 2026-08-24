-- Kingbase/PostgreSQL retains the original URL uniqueness constraint because
-- its index implementation does not have MySQL's utf8mb4 key-width limit.
-- The fixed-width digest is still stored for the common application lookup.
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS source_url_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_ai_news_evidence_event_url_hash
    ON mate_ai_news_event_evidence(event_id, source_url_hash);

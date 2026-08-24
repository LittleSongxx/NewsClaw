-- H2 keeps the URL index for compatibility and also materializes the bounded
-- digest used by the application (the MySQL migration replaces the wide key).
ALTER TABLE mate_ai_news_event_evidence
    ADD COLUMN IF NOT EXISTS source_url_hash VARCHAR(64);
UPDATE mate_ai_news_event_evidence
SET source_url_hash = LOWER(RAWTOHEX(HASH('SHA256', STRINGTOUTF8(source_url))))
WHERE source_url_hash IS NULL;
ALTER TABLE mate_ai_news_event_evidence
    ALTER COLUMN source_url_hash VARCHAR(64) NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ai_news_evidence_event_url_hash
    ON mate_ai_news_event_evidence(event_id, source_url_hash);

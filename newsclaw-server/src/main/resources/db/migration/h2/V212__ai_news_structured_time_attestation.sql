-- Auditable bridge from an approved publisher feed/sitemap item to article-body capture time.
ALTER TABLE mate_ai_news_source_capture
    ADD COLUMN IF NOT EXISTS source_time_origin VARCHAR(32) NOT NULL DEFAULT 'NONE';
ALTER TABLE mate_ai_news_source_capture
    ADD COLUMN IF NOT EXISTS source_time_attestation_status VARCHAR(32) NOT NULL DEFAULT 'NOT_ATTEMPTED';
ALTER TABLE mate_ai_news_source_capture
    ADD COLUMN IF NOT EXISTS source_time_item_version_id BIGINT;
ALTER TABLE mate_ai_news_source_capture
    ADD COLUMN IF NOT EXISTS source_time_attestation_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_ai_news_capture_time_item
    ON mate_ai_news_source_capture(source_time_item_version_id);

UPDATE mate_ai_news_source_capture
   SET source_time_origin = CASE
           WHEN source_published_at IS NULL THEN 'NONE' ELSE 'PAGE_METADATA' END,
       source_time_attestation_status = CASE
           WHEN source_published_at IS NULL THEN 'LEGACY_UNRESOLVED' ELSE 'NOT_REQUIRED' END;

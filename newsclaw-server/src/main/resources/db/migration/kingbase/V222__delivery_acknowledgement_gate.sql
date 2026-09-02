-- Keep operator acknowledgement distinct from a real platform publication.
ALTER TABLE mate_content_item ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE mate_content_item ADD COLUMN IF NOT EXISTS artifact_hash VARCHAR(128);
ALTER TABLE mate_content_item ADD COLUMN IF NOT EXISTS operator_acknowledged_at TIMESTAMP;
ALTER TABLE mate_content_item ADD COLUMN IF NOT EXISTS platform_published_at TIMESTAMP;

ALTER TABLE mate_ai_news_event ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(32) DEFAULT 'none';
ALTER TABLE mate_ai_news_event ADD COLUMN IF NOT EXISTS operator_acknowledged_at TIMESTAMP;
ALTER TABLE mate_ai_news_event ADD COLUMN IF NOT EXISTS platform_published_at TIMESTAMP;
ALTER TABLE mate_ai_news_event ADD COLUMN IF NOT EXISTS platform_external_ref VARCHAR(256);
ALTER TABLE mate_ai_news_event ADD COLUMN IF NOT EXISTS artifact_hash VARCHAR(128);

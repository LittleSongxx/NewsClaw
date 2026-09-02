-- Keep operator acknowledgement distinct from a real platform publication.
ALTER TABLE mate_content_item MODIFY COLUMN status VARCHAR(32) NULL;
ALTER TABLE mate_content_item ADD COLUMN artifact_hash VARCHAR(128) NULL;
ALTER TABLE mate_content_item ADD COLUMN operator_acknowledged_at TIMESTAMP NULL;
ALTER TABLE mate_content_item ADD COLUMN platform_published_at TIMESTAMP NULL;

ALTER TABLE mate_ai_news_event ADD COLUMN delivery_status VARCHAR(32) NULL DEFAULT 'none';
ALTER TABLE mate_ai_news_event ADD COLUMN operator_acknowledged_at TIMESTAMP NULL;
ALTER TABLE mate_ai_news_event ADD COLUMN platform_published_at TIMESTAMP NULL;
ALTER TABLE mate_ai_news_event ADD COLUMN platform_external_ref VARCHAR(256) NULL;
ALTER TABLE mate_ai_news_event ADD COLUMN artifact_hash VARCHAR(128) NULL;

ALTER TABLE mate_ai_news_event_evidence ADD COLUMN IF NOT EXISTS final_url VARCHAR(4096);
ALTER TABLE mate_ai_news_event_evidence ADD COLUMN IF NOT EXISTS fetched_at TIMESTAMP;
ALTER TABLE mate_ai_news_event_evidence ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE mate_ai_news_event_evidence ADD COLUMN IF NOT EXISTS http_status INT;
ALTER TABLE mate_ai_news_event_evidence ADD COLUMN IF NOT EXISTS capture_method VARCHAR(32);
ALTER TABLE mate_ai_news_event_evidence ADD COLUMN IF NOT EXISTS redirect_chain_json CLOB;

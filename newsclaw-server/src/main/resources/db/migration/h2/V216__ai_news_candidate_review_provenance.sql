-- Keep the human adoption gate distinguishable from an Agent/system label.
ALTER TABLE mate_ai_news_candidate
    ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(256);
ALTER TABLE mate_ai_news_candidate
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP(3);
ALTER TABLE mate_ai_news_candidate
    ADD COLUMN IF NOT EXISTS review_origin VARCHAR(32);

ALTER TABLE mate_ai_news_source_capture
    ADD COLUMN extractor_name VARCHAR(64) NULL,
    ADD COLUMN extractor_version VARCHAR(64) NULL,
    ADD COLUMN extractor_config_hash VARCHAR(64) NULL,
    ADD COLUMN extraction_fallback TINYINT NULL,
    ADD COLUMN extraction_warning VARCHAR(512) NULL;

UPDATE mate_ai_news_source_capture
SET extractor_name = 'jsoup_document_text',
    extractor_version = '1',
    extractor_config_hash = 'b5b24093503f71176939d0ca019d389bb065467a56f75888dbf03ea1cec718e0',
    extraction_fallback = 1,
    extraction_warning = 'legacy_capture_backfill'
WHERE capture_status = 'success' AND extractor_name IS NULL;

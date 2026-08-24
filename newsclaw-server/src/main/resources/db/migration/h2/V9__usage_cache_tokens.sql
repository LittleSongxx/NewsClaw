-- V9: Track Anthropic prompt cache token usage
-- RFC-014 Change 4: per-call cache_creation_input_tokens / cache_read_input_tokens
-- accumulated daily so the dashboard can show cache hit rate and cost savings.
-- (was originally numbered V8 but collided with V8__wiki_raw_progress.sql; renumbered to V9.)
ALTER TABLE mate_usage_daily ADD COLUMN IF NOT EXISTS cache_read_tokens BIGINT DEFAULT 0;
ALTER TABLE mate_usage_daily ADD COLUMN IF NOT EXISTS cache_write_tokens BIGINT DEFAULT 0;

-- V8: wiki raw material two-phase digest progress fields, for UI progress bar
-- RFC-012 M2 v2 UI follow-up: expose per-raw progress (current phase + pages done / total planned)
-- so the frontend can render a determinate progress bar instead of an opaque "处理中" badge.
ALTER TABLE mate_wiki_raw_material ADD COLUMN IF NOT EXISTS progress_phase VARCHAR(32) DEFAULT NULL;
ALTER TABLE mate_wiki_raw_material ADD COLUMN IF NOT EXISTS progress_total INT DEFAULT 0;
ALTER TABLE mate_wiki_raw_material ADD COLUMN IF NOT EXISTS progress_done INT DEFAULT 0;

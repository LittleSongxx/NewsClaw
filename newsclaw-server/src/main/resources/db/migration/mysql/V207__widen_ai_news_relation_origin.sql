-- DETERMINISTIC_EXTRACTIVE is 24 characters; V203's VARCHAR(16) rejected
-- the safest runtime attestation exactly when claim and quote were identical.
ALTER TABLE mate_ai_news_event_evidence
    MODIFY COLUMN relation_origin VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';

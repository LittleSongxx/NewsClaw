SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_skill_routine_candidate' AND COLUMN_NAME = 'proposal_id');
SET @s := IF(@c = 0, 'ALTER TABLE mate_skill_routine_candidate ADD COLUMN proposal_id BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_skill_routine_candidate' AND INDEX_NAME = 'idx_skill_routine_proposal');
SET @s := IF(@c = 0, 'CREATE INDEX idx_skill_routine_proposal ON mate_skill_routine_candidate (proposal_id)', 'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

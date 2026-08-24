-- V11: Auto Skill Synthesis (RFC-023)
-- Agent 自治创建 skill 后记录来源对话和安全扫描状态
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS source_conversation_id VARCHAR(64) DEFAULT NULL;
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS security_scan_status VARCHAR(16) DEFAULT NULL;
-- security_scan_status: NULL(旧数据/手动创建) / PASSED / FAILED

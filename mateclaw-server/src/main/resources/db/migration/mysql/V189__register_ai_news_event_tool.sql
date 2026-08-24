-- Register the AI news event/evidence tool.
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000645, 'ai_news_event', 'AI 动态事件', 'AI 行业动态事件与来源证据台账：候选事件去重、官方优先核验、冲突标记、Team Run 和内容关联。', 'builtin', 'aiNewsEventTool', '🛰️', TRUE, TRUE, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE name=VALUES(name), display_name=VALUES(display_name), description=VALUES(description), tool_type=VALUES(tool_type), bean_name=VALUES(bean_name), icon=VALUES(icon), enabled=VALUES(enabled), builtin=VALUES(builtin), update_time=VALUES(update_time), deleted=VALUES(deleted);

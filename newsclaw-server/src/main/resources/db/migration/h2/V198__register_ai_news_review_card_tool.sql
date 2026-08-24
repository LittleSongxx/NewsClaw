MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000646, 'ai_news_review_card', '飞书 AI 动态复核卡', '把结构化事件 ID 发送为飞书人工复核卡；点击操作受身份、workspace 和事件状态机约束。', 'builtin', 'aiNewsReviewCardTool', 'pi:check-square', TRUE, TRUE, NOW(), NOW(), 0);

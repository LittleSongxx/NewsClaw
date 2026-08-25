-- Surface the provenance-preserving source actions in existing workspaces.
UPDATE mate_tool
SET description = 'AI 行业动态事件与来源证据台账：结构化来源发现（RSS/SearXNG/官方 API provenance）、候选事件去重、官方优先核验、冲突标记、Team Run 和内容关联。来源发现只读，不会自动核验或发布。',
    update_time = NOW()
WHERE id = 1000000645 AND name = 'ai_news_event' AND deleted = 0;

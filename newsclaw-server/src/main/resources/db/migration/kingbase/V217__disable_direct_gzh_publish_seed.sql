-- Align existing seeded tool metadata with the runtime safety gate.  V168 is
-- intentionally immutable after release; existing databases get this delta.
UPDATE mate_tool
SET description = '将生成的图文推进微信公众号草稿箱：action=draft 上传封面并存入草稿。直接 action=publish 已禁用，最终发表必须由账号 owner 在公众号后台人工完成。需在系统设置配置 weixinoa.app_id / weixinoa.app_secret。',
    update_time = CURRENT_TIMESTAMP
WHERE id = 1000000631 AND name = 'GzhPublishTool';

UPDATE mate_agent
SET system_prompt = REPLACE(system_prompt,
    '发布是外向且不可逆的动作：调用 gzh_publish 前必须展示最终内容并获得用户明确确认；未经 confirmPublish=true 与用户认可，绝不群发。',
    '发布是外向且不可逆的动作：NewsClaw 只负责生成草稿，直接群发入口已禁用；账号 owner 必须在公众号后台核对并人工发表。'),
    update_time = CURRENT_TIMESTAMP
WHERE id = 1000000640 AND name = '内容工作室';

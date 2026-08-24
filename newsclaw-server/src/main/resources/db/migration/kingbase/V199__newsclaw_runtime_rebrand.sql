-- Runtime-only product rebrand for system-owned records.
-- Historic migrations remain immutable. Conversations, audit records, raw
-- evidence, and delivered content are intentionally outside this migration.

-- The source tree and built-in resource directory now use the new identifier.
-- Only system agents have no creator; this keeps user-authored prompts intact.
UPDATE mate_agent
SET name = REPLACE(REPLACE(REPLACE(REPLACE(name, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    description = REPLACE(REPLACE(REPLACE(REPLACE(description, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    system_prompt = REPLACE(REPLACE(REPLACE(REPLACE(system_prompt, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    workspace_base_path = REPLACE(REPLACE(REPLACE(REPLACE(workspace_base_path, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    runtime_config = REPLACE(REPLACE(REPLACE(REPLACE(runtime_config, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    update_time = CURRENT_TIMESTAMP
WHERE creator_user_id IS NULL
  AND (
      COALESCE(name, '') LIKE '%' || 'Mate' || 'Claw' || '%'
      OR COALESCE(name, '') LIKE '%' || 'mate' || 'Claw' || '%'
      OR COALESCE(name, '') LIKE '%' || 'mate' || 'claw' || '%'
      OR COALESCE(description, '') LIKE '%' || 'Mate' || 'Claw' || '%'
      OR COALESCE(description, '') LIKE '%' || 'mate' || 'Claw' || '%'
      OR COALESCE(description, '') LIKE '%' || 'mate' || 'claw' || '%'
      OR COALESCE(system_prompt, '') LIKE '%' || 'Mate' || 'Claw' || '%'
      OR COALESCE(system_prompt, '') LIKE '%' || 'mate' || 'Claw' || '%'
      OR COALESCE(system_prompt, '') LIKE '%' || 'mate' || 'claw' || '%'
      OR COALESCE(workspace_base_path, '') LIKE '%' || 'mate' || 'claw' || '%'
      OR COALESCE(runtime_config, '') LIKE '%' || 'Mate' || 'Claw' || '%'
      OR COALESCE(runtime_config, '') LIKE '%' || 'mate' || 'Claw' || '%'
      OR COALESCE(runtime_config, '') LIKE '%' || 'mate' || 'claw' || '%'
      OR COALESCE(runtime_config, '') LIKE '%' || 'vip.' || 'mate' || '%'
  );

-- Built-in Skills are operational assets. Do not rewrite user-created Skills.
UPDATE mate_skill
SET name = REPLACE(REPLACE(REPLACE(REPLACE(name, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    description = REPLACE(REPLACE(REPLACE(REPLACE(description, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    author = REPLACE(REPLACE(REPLACE(REPLACE(author, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    config_json = REPLACE(REPLACE(REPLACE(REPLACE(config_json, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    source_code = REPLACE(REPLACE(REPLACE(REPLACE(source_code, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    skill_content = REPLACE(REPLACE(REPLACE(REPLACE(skill_content, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    tags = REPLACE(REPLACE(REPLACE(REPLACE(tags, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    security_scan_result = REPLACE(REPLACE(REPLACE(REPLACE(security_scan_result, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    name_zh = REPLACE(REPLACE(REPLACE(REPLACE(name_zh, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    name_en = REPLACE(REPLACE(REPLACE(REPLACE(name_en, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    manifest_json = REPLACE(REPLACE(REPLACE(REPLACE(manifest_json, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    origin = REPLACE(REPLACE(REPLACE(REPLACE(origin, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    update_time = CURRENT_TIMESTAMP
WHERE builtin = TRUE
  AND (
      LOWER(CONCAT_WS(' ', name, description, author, config_json, source_code, skill_content, tags, security_scan_result, name_zh, name_en, manifest_json, origin)) LIKE '%' || 'mate' || 'claw' || '%'
      OR LOWER(CONCAT_WS(' ', name, description, author, config_json, source_code, skill_content, tags, security_scan_result, name_zh, name_en, manifest_json, origin)) LIKE '%' || 'vip.' || 'mate' || '%'
  );

-- Built-in publisher/tool descriptors follow the same contract as their beans.
UPDATE mate_tool
SET name = REPLACE(REPLACE(REPLACE(REPLACE(name, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    display_name = REPLACE(REPLACE(REPLACE(REPLACE(display_name, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    description = REPLACE(REPLACE(REPLACE(REPLACE(description, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    bean_name = REPLACE(REPLACE(REPLACE(REPLACE(bean_name, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    mcp_endpoint = REPLACE(REPLACE(REPLACE(REPLACE(mcp_endpoint, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    params_schema = REPLACE(REPLACE(REPLACE(REPLACE(params_schema, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'vip.' || 'mate', 'vip.newsclaw'),
    update_time = CURRENT_TIMESTAMP
WHERE builtin = TRUE
  AND (
      LOWER(CONCAT_WS(' ', name, display_name, description, bean_name, mcp_endpoint, params_schema)) LIKE '%' || 'mate' || 'claw' || '%'
      OR LOWER(CONCAT_WS(' ', name, display_name, description, bean_name, mcp_endpoint, params_schema)) LIKE '%' || 'vip.' || 'mate' || '%'
  );

-- Setting values can contain encrypted user credentials, so only system-key
-- names and their descriptions are changed. Values are deliberately untouched.
UPDATE mate_system_setting
SET setting_key = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(setting_key, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'MATE' || 'CLAW', 'NEWSCLAW'), 'mate' || '.', 'newsclaw.'), 'vip.' || 'mate', 'vip.newsclaw'),
    description = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(description, 'Mate' || 'Claw', 'NewsClaw'), 'mate' || 'Claw', 'newsClaw'), 'mate' || 'claw', 'newsclaw'), 'MATE' || 'CLAW', 'NEWSCLAW'), 'mate' || '.', 'newsclaw.'), 'vip.' || 'mate', 'vip.newsclaw'),
    update_time = CURRENT_TIMESTAMP
WHERE setting_key LIKE 'mate' || 'claw' || '.%'
   OR setting_key LIKE 'mate' || 'claw' || '\_%'
   OR setting_key LIKE 'MATE' || 'CLAW' || '\_%';

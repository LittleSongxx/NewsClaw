-- Runtime-only product rebrand for system-owned records.
-- Historic migrations remain immutable. Conversations, audit records, raw
-- evidence, and delivered content are intentionally outside this migration.

UPDATE mate_agent
SET name = REPLACE(REPLACE(REPLACE(REPLACE(name, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    description = REPLACE(REPLACE(REPLACE(REPLACE(description, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    system_prompt = REPLACE(REPLACE(REPLACE(REPLACE(system_prompt, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    workspace_base_path = REPLACE(REPLACE(REPLACE(REPLACE(workspace_base_path, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    runtime_config = REPLACE(REPLACE(REPLACE(REPLACE(runtime_config, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    update_time = CURRENT_TIMESTAMP
WHERE creator_user_id IS NULL
  AND (
      COALESCE(name, '') LIKE CONCAT('%', 'Mate', 'Claw', '%')
      OR COALESCE(name, '') LIKE CONCAT('%', 'mate', 'Claw', '%')
      OR COALESCE(name, '') LIKE CONCAT('%', 'mate', 'claw', '%')
      OR COALESCE(description, '') LIKE CONCAT('%', 'Mate', 'Claw', '%')
      OR COALESCE(description, '') LIKE CONCAT('%', 'mate', 'Claw', '%')
      OR COALESCE(description, '') LIKE CONCAT('%', 'mate', 'claw', '%')
      OR COALESCE(system_prompt, '') LIKE CONCAT('%', 'Mate', 'Claw', '%')
      OR COALESCE(system_prompt, '') LIKE CONCAT('%', 'mate', 'Claw', '%')
      OR COALESCE(system_prompt, '') LIKE CONCAT('%', 'mate', 'claw', '%')
      OR COALESCE(workspace_base_path, '') LIKE CONCAT('%', 'mate', 'claw', '%')
      OR COALESCE(runtime_config, '') LIKE CONCAT('%', 'Mate', 'Claw', '%')
      OR COALESCE(runtime_config, '') LIKE CONCAT('%', 'mate', 'Claw', '%')
      OR COALESCE(runtime_config, '') LIKE CONCAT('%', 'mate', 'claw', '%')
      OR COALESCE(runtime_config, '') LIKE CONCAT('%', 'vip.', 'mate', '%')
  );

UPDATE mate_skill
SET name = REPLACE(REPLACE(REPLACE(REPLACE(name, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    description = REPLACE(REPLACE(REPLACE(REPLACE(description, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    author = REPLACE(REPLACE(REPLACE(REPLACE(author, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    config_json = REPLACE(REPLACE(REPLACE(REPLACE(config_json, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    source_code = REPLACE(REPLACE(REPLACE(REPLACE(source_code, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    skill_content = REPLACE(REPLACE(REPLACE(REPLACE(skill_content, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    tags = REPLACE(REPLACE(REPLACE(REPLACE(tags, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    security_scan_result = REPLACE(REPLACE(REPLACE(REPLACE(security_scan_result, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    name_zh = REPLACE(REPLACE(REPLACE(REPLACE(name_zh, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    name_en = REPLACE(REPLACE(REPLACE(REPLACE(name_en, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    manifest_json = REPLACE(REPLACE(REPLACE(REPLACE(manifest_json, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    origin = REPLACE(REPLACE(REPLACE(REPLACE(origin, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    update_time = CURRENT_TIMESTAMP
WHERE builtin = TRUE
  AND (
      LOWER(CONCAT_WS(' ', name, description, author, config_json, source_code, skill_content, tags, security_scan_result, name_zh, name_en, manifest_json, origin)) LIKE CONCAT('%', 'mate', 'claw', '%')
      OR LOWER(CONCAT_WS(' ', name, description, author, config_json, source_code, skill_content, tags, security_scan_result, name_zh, name_en, manifest_json, origin)) LIKE CONCAT('%', 'vip.', 'mate', '%')
  );

UPDATE mate_tool
SET name = REPLACE(REPLACE(REPLACE(REPLACE(name, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    display_name = REPLACE(REPLACE(REPLACE(REPLACE(display_name, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    description = REPLACE(REPLACE(REPLACE(REPLACE(description, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    bean_name = REPLACE(REPLACE(REPLACE(REPLACE(bean_name, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    mcp_endpoint = REPLACE(REPLACE(REPLACE(REPLACE(mcp_endpoint, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    params_schema = REPLACE(REPLACE(REPLACE(REPLACE(params_schema, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    update_time = CURRENT_TIMESTAMP
WHERE builtin = TRUE
  AND (
      LOWER(CONCAT_WS(' ', name, display_name, description, bean_name, mcp_endpoint, params_schema)) LIKE CONCAT('%', 'mate', 'claw', '%')
      OR LOWER(CONCAT_WS(' ', name, display_name, description, bean_name, mcp_endpoint, params_schema)) LIKE CONCAT('%', 'vip.', 'mate', '%')
  );

UPDATE mate_system_setting
SET setting_key = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(setting_key, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('MATE', 'CLAW'), 'NEWSCLAW'), CONCAT('mate', '.'), 'newsclaw.'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    description = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(description, CONCAT('Mate', 'Claw'), 'NewsClaw'), CONCAT('mate', 'Claw'), 'newsClaw'), CONCAT('mate', 'claw'), 'newsclaw'), CONCAT('MATE', 'CLAW'), 'NEWSCLAW'), CONCAT('mate', '.'), 'newsclaw.'), CONCAT('vip.', 'mate'), 'vip.newsclaw'),
    update_time = CURRENT_TIMESTAMP
WHERE setting_key LIKE CONCAT('mate', 'claw', '.%')
   OR setting_key LIKE CONCAT('mate', 'claw', '\\_%')
   OR setting_key LIKE CONCAT('MATE', 'CLAW', '\\_%');

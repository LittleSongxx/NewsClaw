"""
系统预置 AgentProfile 定义 + 首次启动自动部署
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from .profile import AgentProfile, AgentType, ProfileStore, SkillsMode, get_profile_store

if TYPE_CHECKING:
    from pathlib import Path

logger = logging.getLogger(__name__)


def _newsroom_editor_directive() -> str:
    """ai-news-editor 的编辑方针。单一来源在 newsroom.prompts，此处延迟
    导入以保持 presets 模块导入图轻盈。"""
    from ..newsroom.prompts import EDITOR_DIRECTIVE

    return EDITOR_DIRECTIVE


SYSTEM_PRESETS: list[AgentProfile] = [
    # ── 通用基础 ──────────────────────────────────────────────────────
    AgentProfile(
        id="default",
        name="小小宋",
        description="通用全能助手，拥有所有技能",
        type=AgentType.SYSTEM,
        skills=[],
        skills_mode=SkillsMode.ALL,
        # NewsClaw 收敛：聊天默认不暴露这些工具族（组织编排 / Agent 包分发 /
        # 技能商店 / 代码分析 / 配置 / worktree / 插件管理）。exclusive 模式下
        # 只列要隐藏的；功能仍在，需要时把对应名字从本清单移除即可恢复。
        tools=[
            # 组织编排
            "setup_organization",
            # Agent 包分发（含批量导出与详情查询）
            "export_agent", "import_agent", "inspect_agent_package", "publish_agent",
            "search_hub_agents", "install_hub_agent", "list_exportable_agents",
            "batch_export_agents", "get_hub_agent_detail",
            # 技能商店
            "search_store_skills", "install_store_skill", "submit_skill_repo",
            "get_store_skill_detail",
            # 代码分析 / Notebook（保留 read_lints：代码修改规范依赖它做 linter 自检）
            "lsp", "semantic_search", "edit_notebook",
            # 配置 / worktree / 插件管理
            "system_config",
            "enter_worktree", "exit_worktree",
            "list_plugins", "get_plugin_info",
        ],
        tools_mode="exclusive",
        custom_prompt="",
        icon="🐕",
        color="#4A90D9",
        category="general",
        fallback_profile_id=None,
        created_by="system",
        name_i18n={"zh": "小小宋", "en": "XiaoXiaoSong"},
        description_i18n={
            "zh": "通用全能助手，拥有所有技能",
            "en": "General-purpose assistant with all skills",
        },
    ),
    # ── AI 早报主线 ───────────────────────────────────────────────────
    # ai-news-editor：AI 早报主线（src/newsclaw/newsroom/）的执行者。
    # 每日管线 / 每周复盘两条定时任务（newsroom_daily_pipeline /
    # newsroom_weekly_review）由 newsroom.seed 播种并指向本预设。
    AgentProfile(
        id="ai-news-editor",
        name="早报编辑",
        description="AI 早报主线：采集 AI 圈新闻，产出小红书/公众号/日报三产物并沉淀 Wiki",
        type=AgentType.SYSTEM,
        skills=[
            "newsclaw/skills@newsroom-editor",
            "newsclaw/skills@xiaohongshu-creator",
            "newsclaw/skills@wechat-article",
            "newsclaw/skills@chinese-writing",
            "newsclaw/skills@obsidian-skills",
            "newsclaw/skills@feishu-cli",
        ],
        skills_mode=SkillsMode.INCLUSIVE,
        # 显式工具白名单（NewsClaw 收敛）：只保留主线真正需要的四个能力面 +
        # 并行采集（委派）+ 交付（飞书推送）。相比类别清单砍掉 22 个工具：
        # browser×13（site 源用 web_fetch 足够）、MCP×5（未接任何 MCP 服务）、
        # 内存分析×3、技能管理×3（保留 get_skill_info）、delete/move_file。
        # 类别名与具体工具名可混用（expand_tool_categories 支持）。
        tools=[
            # 文件
            "read_file", "write_file", "edit_file", "list_directory", "glob", "grep", "run_shell",
            # 检索
            "web_search", "news_search", "web_fetch",
            # 记忆与技能说明
            "search_memory", "add_memory", "get_skill_info",
            # 自省与按需发现
            "get_tool_info", "tool_search",
            # 并行采集（多 Agent supervisor-workers）
            "delegate_parallel", "delegate_to_agent", "task_stop",
            # 交付与沉淀：本地 Wiki 知识库 + 飞书私聊推送 + 飞书云文档归档
            "deliver_artifacts", "feishu_doc", "wiki_upsert",
        ],
        tools_mode="inclusive",
        # 未接任何 MCP 服务：inclusive + 空列表 = 不放行 MCP 网关工具
        mcp_mode="inclusive",
        # 编辑方针单一来源：newsroom.prompts.EDITOR_DIRECTIVE（随主线一起维护）
        custom_prompt=_newsroom_editor_directive(),
        icon="📰",
        color="#E85D3D",
        category="content",
        fallback_profile_id="default",
        created_by="system",
        name_i18n={"zh": "早报编辑", "en": "AI News Editor"},
        description_i18n={
            "zh": "AI 早报主线：采集 AI 圈新闻，产出小红书/公众号/日报三产物并沉淀 Wiki",
            "en": "AI newsroom: daily briefing, Xiaohongshu/WeChat drafts, wiki archival",
        },
    ),
    # ── 内容创作 ──────────────────────────────────────────────────────
    AgentProfile(
        id="content-creator",
        name="自媒体达人",
        description="小红书/公众号内容改写与打磨，平台调性适配",
        type=AgentType.SYSTEM,
        skills=[
            "newsclaw/skills@xiaohongshu-creator",
            "newsclaw/skills@wechat-article",
            "newsclaw/skills@chinese-writing",
            "newsclaw/skills@content-research-writer",
        ],
        skills_mode=SkillsMode.INCLUSIVE,
        tools=["filesystem", "memory", "skills", "research"],
        tools_mode="inclusive",
        custom_prompt=(
            "你是自媒体内容创作专家。擅长为小红书、微信公众号撰写与改写文案，"
            "根据平台特点调整文风：小红书注重种草和视觉吸引，公众号注重深度和阅读体验。"
            "始终关注用户的内容定位和目标受众。"
        ),
        icon="✍️",
        color="#FF6B6B",
        category="content",
        fallback_profile_id="default",
        created_by="system",
        name_i18n={"zh": "自媒体达人", "en": "Content Creator"},
        description_i18n={
            "zh": "小红书/公众号内容改写与打磨，平台调性适配",
            "en": "Xiaohongshu/WeChat content polishing",
        },
    ),
    # ── 办公文档 ──────────────────────────────────────────────────────
    AgentProfile(
        id="office-doc",
        name="文助",
        description="办公文档处理专家，擅长 Word/PPT/Excel/PDF",
        type=AgentType.SYSTEM,
        skills=[
            "newsclaw/skills@docx",
            "newsclaw/skills@pptx",
            "newsclaw/skills@xlsx",
            "newsclaw/skills@pdf",
        ],
        skills_mode=SkillsMode.INCLUSIVE,
        tools=["filesystem", "skills", "memory"],
        tools_mode="inclusive",
        custom_prompt=(
            "你是办公文档处理专家。优先使用文档相关工具处理用户需求。"
            "如果用户需求超出文档处理范围，建议用户切换到通用助手。"
        ),
        icon="📄",
        color="#27AE60",
        category="enterprise",
        fallback_profile_id="default",
        created_by="system",
        name_i18n={"zh": "文助", "en": "DocHelper"},
        description_i18n={
            "zh": "办公文档处理专家，擅长 Word/PPT/Excel/PDF",
            "en": "Office document specialist for Word/PPT/Excel/PDF",
        },
    ),
]

def deploy_system_presets(store: ProfileStore) -> int:
    """
    部署系统预置 Profile（首次启动或升级时调用）。

    - 不存在的预置 Profile 直接创建
    - user_customized=True 的跳过（尊重用户的自定义修改）
    - 未被用户自定义的 SYSTEM Profile 若 skills/category 与预置不同则同步更新

    Returns:
        新增或升级的 Profile 数量
    """
    deployed = 0
    for preset in SYSTEM_PRESETS:
        if not store.exists(preset.id):
            store.save(preset)
            deployed += 1
            logger.info(f"Deployed system preset: {preset.id} ({preset.name})")
        else:
            existing = store.get(preset.id)
            if existing and existing.is_system:
                if existing.user_customized:
                    logger.debug(f"Skipping customized preset: {preset.id} (user_customized=True)")
                    continue
                needs_upgrade = (
                    sorted(existing.skills) != sorted(preset.skills)
                    or existing.category != preset.category
                    or sorted(existing.tools) != sorted(preset.tools)
                    or existing.tools_mode != preset.tools_mode
                    # 名称/描述也会随预设迭代（如默认助手改名），必须一并同步，
                    # 否则存量 store 会长期停留在旧名（user_customized 已在上方排除）
                    or existing.name != preset.name
                    or (existing.name_i18n or {}) != (preset.name_i18n or {})
                    or existing.description != preset.description
                )
                if needs_upgrade:
                    data = existing.to_dict()
                    data["name"] = preset.name
                    data["name_i18n"] = preset.name_i18n
                    data["description"] = preset.description
                    data["description_i18n"] = preset.description_i18n
                    data["skills"] = preset.skills
                    data["skills_mode"] = preset.skills_mode.value
                    data["category"] = preset.category
                    data["tools"] = preset.tools
                    data["tools_mode"] = preset.tools_mode
                    data["mcp_servers"] = preset.mcp_servers
                    data["mcp_mode"] = preset.mcp_mode
                    data["plugins"] = preset.plugins
                    data["plugins_mode"] = preset.plugins_mode
                    updated = AgentProfile.from_dict(data)
                    store._cache[preset.id] = updated
                    store._persist(updated)
                    deployed += 1
                    logger.info(f"Upgraded system preset: {preset.id} (skills/category synced)")
    # 剪枝：同步清理存量存储中已从 SYSTEM_PRESETS 移除的旧预设，
    # 否则它们会继续出现在 Agent 选择器中（NewsClaw fork 收敛后引入）。
    pruned = store.prune_system_presets({preset.id for preset in SYSTEM_PRESETS})
    if pruned:
        logger.info(f"Pruned {len(pruned)} retired system preset(s)")

    if deployed:
        logger.info(f"Deployed/upgraded {deployed} system preset profile(s)")
    return deployed


def get_preset_by_id(profile_id: str) -> AgentProfile | None:
    """按 ID 查找系统预设原始定义（用于恢复默认）。"""
    return next((p for p in SYSTEM_PRESETS if p.id == profile_id), None)


def ensure_presets_on_mode_enable(agents_dir: str | Path) -> None:
    """
    多Agent模式首次开启时调用，确保预置 Profile 已部署。

    Args:
        agents_dir: data/agents/ 目录路径
    """
    from pathlib import Path

    agents_dir = Path(agents_dir)
    store = get_profile_store(agents_dir)
    deployed = deploy_system_presets(store)
    if deployed:
        logger.info(f"Multi-agent mode enabled: deployed {deployed} preset(s) to {agents_dir}")

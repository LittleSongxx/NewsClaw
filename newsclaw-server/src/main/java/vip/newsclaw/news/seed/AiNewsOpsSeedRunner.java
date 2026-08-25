package vip.newsclaw.news.seed;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.newsclaw.agent.binding.model.AgentToolBinding;
import vip.newsclaw.agent.binding.repository.AgentToolBindingMapper;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.channel.model.ChannelEntity;
import vip.newsclaw.channel.model.ChannelSessionEntity;
import vip.newsclaw.channel.repository.ChannelMapper;
import vip.newsclaw.channel.repository.ChannelSessionMapper;
import vip.newsclaw.config.EnvironmentConfig;
import vip.newsclaw.cron.model.CronJobEntity;
import vip.newsclaw.cron.repository.CronJobMapper;
import vip.newsclaw.cron.model.DeliveryConfig;
import vip.newsclaw.agent.context.ChannelTarget;
import vip.newsclaw.skill.model.SkillEntity;
import vip.newsclaw.skill.repository.SkillMapper;
import vip.newsclaw.team.model.AgentTeamEntity;
import vip.newsclaw.team.repository.AgentTeamMapper;
import vip.newsclaw.team.service.TeamService;
import vip.newsclaw.tool.model.ToolEntity;
import vip.newsclaw.tool.repository.ToolMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the vertical AI-news operating team after the regular database seed.
 * It is intentionally idempotent. The discovery radar is enabled when the
 * deployment-owned model chain and a domestic IM target are ready; the weekly
 * production job remains disabled because it always requires user approval.
 */
@Slf4j
@Component
@Order(120)
public class AiNewsOpsSeedRunner implements ApplicationRunner {

    private static final long WORKSPACE_ID = 1L;
    private static final String TEAM_NAME = "AI 动态内容生产组";
    private static final String DEFAULT_IM_TYPE = "feishu";
    private static final String DEFAULT_IM_NAME = "AI 动态默认飞书";
    private static final String RADAR_CRON_NAME = "每日 AI 动态雷达";
    private static final String WEEKLY_CRON_NAME = "AI 动态周报生产";
    /**
     * A Web/WebChat row is not an IM delivery target.  If any real domestic
     * IM row already exists, its owner has made an explicit channel choice and
     * the vertical seed must leave that choice untouched.
     */
    private static final Set<String> DOMESTIC_IM_TYPES = Set.of(
            "feishu", "dingtalk", "wecom", "weixin", "qq");
    private static final List<String> LEAD_REQUIRED_TOOLS = List.of(
            "ai_news_event", "ai_news_review_card", "TeamTasksTool", "ChannelMessageTool");

    private final AgentMapper agentMapper;
    private final AgentToolBindingMapper agentToolBindingMapper;
    private final ChannelMapper channelMapper;
    private final ChannelSessionMapper channelSessionMapper;
    private final AgentTeamMapper teamMapper;
    private final TeamService teamService;
    private final CronJobMapper cronJobMapper;
    private final SkillMapper skillMapper;
    private final ToolMapper toolMapper;

    @Autowired
    public AiNewsOpsSeedRunner(AgentMapper agentMapper,
                               AgentToolBindingMapper agentToolBindingMapper,
                               ChannelMapper channelMapper,
                               ChannelSessionMapper channelSessionMapper,
                               AgentTeamMapper teamMapper,
                               TeamService teamService,
                               CronJobMapper cronJobMapper,
                               SkillMapper skillMapper,
                               ToolMapper toolMapper) {
        this.agentMapper = agentMapper;
        this.agentToolBindingMapper = agentToolBindingMapper;
        this.channelMapper = channelMapper;
        this.channelSessionMapper = channelSessionMapper;
        this.teamMapper = teamMapper;
        this.teamService = teamService;
        this.cronJobMapper = cronJobMapper;
        this.skillMapper = skillMapper;
        this.toolMapper = toolMapper;
    }

    /** Source-compatible constructor used by callers without tool binding support. */
    public AiNewsOpsSeedRunner(AgentMapper agentMapper,
                               ChannelMapper channelMapper,
                               ChannelSessionMapper channelSessionMapper,
                               AgentTeamMapper teamMapper,
                               TeamService teamService,
                               CronJobMapper cronJobMapper,
                               SkillMapper skillMapper,
                               ToolMapper toolMapper) {
        this(agentMapper, null, channelMapper, channelSessionMapper, teamMapper, teamService,
                cronJobMapper, skillMapper, toolMapper);
    }

    /** Source-compatible constructor used by older tests and embedders. */
    public AiNewsOpsSeedRunner(AgentMapper agentMapper,
                               ChannelMapper channelMapper,
                               AgentTeamMapper teamMapper,
                               TeamService teamService,
                               CronJobMapper cronJobMapper,
                               SkillMapper skillMapper,
                               ToolMapper toolMapper) {
        this(agentMapper, null, channelMapper, null, teamMapper, teamService,
                cronJobMapper, skillMapper, toolMapper);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            disableUnrelatedProductSurface();
            List<AgentEntity> agents = ensureAgents();
            ensureLeadToolScope(agents.getFirst());
            ChannelEntity notificationChannel = resolveNotificationChannel(agents.get(0).getId());
            ensureTeam(agents);
            ensureCronJobs(agents.get(0).getId(), notificationChannel);
            log.info("[AiNewsOps] vertical seed ready: agents={}, team={}", agents.size(), TEAM_NAME);
        } catch (Exception e) {
            // A legacy database may be mid-upgrade; the core server must still
            // start and the next boot can retry the idempotent seed.
            log.warn("[AiNewsOps] vertical seed skipped: {}", e.getMessage());
        }
    }

    /**
     * Upgrade the legacy unscoped lead to the smallest tool surface required
     * by its scheduled and interactive workflows. Any existing row means an
     * operator has already made an explicit choice, so the seed leaves the
     * entire binding set untouched.
     */
    private void ensureLeadToolScope(AgentEntity lead) {
        if (agentToolBindingMapper == null || lead == null || lead.getId() == null) {
            return;
        }
        List<AgentToolBinding> existing = agentToolBindingMapper.selectList(
                Wrappers.<AgentToolBinding>lambdaQuery()
                        .eq(AgentToolBinding::getAgentId, lead.getId()));
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (String toolName : LEAD_REQUIRED_TOOLS) {
            AgentToolBinding binding = new AgentToolBinding();
            binding.setAgentId(lead.getId());
            binding.setToolName(toolName);
            binding.setEnabled(true);
            binding.setCreateTime(now);
            binding.setUpdateTime(now);
            binding.setDeleted(0);
            agentToolBindingMapper.insert(binding);
        }
        log.info("[AiNewsOps] scoped lead agent {} to {} required tools",
                lead.getId(), LEAD_REQUIRED_TOOLS.size());
    }

    /**
     * Provision the vertical's default IM entry when Docker supplied Feishu
     * credentials and this workspace has no domestic IM choice yet.
     *
     * <p>Secrets intentionally stay out of {@code mate_channel.config_json}:
     * {@link EnvironmentConfig} overlays them at adapter/preflight time.  The
     * row is therefore safe to export and remains useful when a credential is
     * rotated without editing the database.</p>
     */
    /**
     * Legacy reflective entry point retained for older seed tests/embedders.
     * It deliberately only touches the channel table when Feishu bootstrap
     * credentials are present, preserving the old no-credentials contract.
     */
    private void ensureDefaultFeishuChannel(Long leadAgentId) {
        provisionDefaultFeishuChannel(leadAgentId);
    }

    private ChannelEntity provisionDefaultFeishuChannel(Long leadAgentId) {
        if (!hasFeishuBootstrapCredentials()) {
            log.info("[AiNewsOps] Feishu default not seeded: FEISHU_APP_ID/APP_SECRET are not configured");
            return null;
        }

        List<ChannelEntity> existing = channelMapper.selectList(Wrappers.<ChannelEntity>lambdaQuery()
                .eq(ChannelEntity::getWorkspaceId, WORKSPACE_ID)
                .eq(ChannelEntity::getDeleted, 0));
        ChannelEntity selected = existing.stream()
                .filter(channel -> DOMESTIC_IM_TYPES.contains(channel.getChannelType()))
                .findFirst().orElse(null);
        if (selected != null) {
            return selected;
        }
        ChannelEntity named = existing.stream()
                .filter(channel -> DEFAULT_IM_NAME.equals(channel.getName()))
                .findFirst().orElse(null);
        if (named != null) {
            return named;
        }

        ChannelEntity channel = new ChannelEntity();
        channel.setName(DEFAULT_IM_NAME);
        channel.setChannelType(DEFAULT_IM_TYPE);
        channel.setAgentId(leadAgentId);
        channel.setBotPrefix("");
        // Credentials are supplied by FEISHU_* environment variables.  Keep
        // only transport defaults in the row so secrets never enter SQL or
        // the admin list API.
        channel.setConfigJson("{\"connection_mode\":\"websocket\",\"domain\":\"feishu\"}");
        channel.setEnabled(true);
        channel.setDescription("AI 动态雷达默认通知渠道，凭证由 .env 的 FEISHU_* 注入");
        channel.setWorkspaceId(WORKSPACE_ID);
        channel.setCreateTime(LocalDateTime.now());
        channel.setUpdateTime(LocalDateTime.now());
        channel.setDeleted(0);
        channelMapper.insert(channel);
        log.info("[AiNewsOps] seeded default Feishu channel id={} for workspace={}",
                channel.getId(), WORKSPACE_ID);
        return channel;
    }

    private ChannelEntity resolveNotificationChannel(Long leadAgentId) {
        ChannelEntity provisioned = provisionDefaultFeishuChannel(leadAgentId);
        return provisioned != null ? provisioned : findNotificationChannel();
    }

    private boolean hasFeishuBootstrapCredentials() {
        if (!EnvironmentConfig.enabled()) {
            return false;
        }
        Map<String, Object> effective = EnvironmentConfig.effectiveChannelConfig(
                DEFAULT_IM_TYPE, Map.of());
        return usableCredential(effective.get("app_id"))
                && usableCredential(effective.get("app_secret"));
    }

    private boolean usableCredential(Object value) {
        if (value == null || value.toString().isBlank()) {
            return false;
        }
        String normalized = value.toString().trim().toLowerCase();
        return !normalized.startsWith("<")
                && !normalized.startsWith("replace-with-")
                && !normalized.startsWith("your-")
                && !normalized.startsWith("change-me-");
    }

    private List<AgentEntity> ensureAgents() {
        List<AgentSpec> specs = List.of(
                new AgentSpec("AI 动态主编", "统筹选题、核验、Team Run 和人工审批。", "lead"),
                new AgentSpec("热点发现员", "按来源注册表发现全球与中国 AI 动态候选。", "discover"),
                new AgentSpec("事实核查员", "检查官方来源、交叉证据、claims 冲突和置信度。", "verify"),
                new AgentSpec("内容编辑", "将已核验事件改写为原创公众号和小红书内容。", "edit"),
                new AgentSpec("视觉编辑", "生成公众号封面和小红书 3:4 卡片素材。", "visual"),
                new AgentSpec("合规交付员", "执行去 AI 化、合规扫描、草稿箱和素材包交付。", "delivery")
        );
        List<AgentEntity> result = new ArrayList<>();
        for (AgentSpec spec : specs) {
            AgentEntity agent = agentMapper.selectOne(Wrappers.<AgentEntity>lambdaQuery()
                    .eq(AgentEntity::getWorkspaceId, WORKSPACE_ID)
                    .eq(AgentEntity::getName, spec.name())
                    .eq(AgentEntity::getDeleted, 0));
            if (agent == null) {
                agent = new AgentEntity();
                agent.setName(spec.name());
                agent.setDescription(spec.description());
                agent.setAgentType("react");
                agent.setSystemPrompt(promptFor(spec));
                agent.setMaxIterations(80);
                agent.setEnabled(true);
                agent.setIcon("pi:cpu");
                agent.setTags("ai-news,content-ops," + spec.tag());
                agent.setWorkspaceId(WORKSPACE_ID);
                agent.setCreateTime(LocalDateTime.now());
                agent.setUpdateTime(LocalDateTime.now());
                agent.setDeleted(0);
                agentMapper.insert(agent);
            } else {
                // A previous run may have left a vertical agent disabled. The
                // vertical seed owns these agents, so make the intended
                // operating surface deterministic on every boot.  The first
                // version of this vertical used a generic prompt, which let a
                // worker submit a DOCX/report in place of an actual delivery
                // tool call. Upgrade only the known legacy prompt; a later
                // operator-authored prompt remains untouched.
                boolean changed = false;
                if (!Boolean.TRUE.equals(agent.getEnabled())) {
                    agent.setEnabled(true);
                    changed = true;
                }
                if (isLegacyVerticalPrompt(agent.getSystemPrompt())) {
                    agent.setSystemPrompt(promptFor(spec));
                    changed = true;
                }
                if (changed) {
                    agent.setUpdateTime(LocalDateTime.now());
                    agentMapper.updateById(agent);
                }
            }
            result.add(agent);
        }
        return result;
    }

    private void ensureTeam(List<AgentEntity> agents) {
        AgentTeamEntity existing = teamMapper.selectOne(Wrappers.<AgentTeamEntity>lambdaQuery()
                .eq(AgentTeamEntity::getWorkspaceId, WORKSPACE_ID)
                .eq(AgentTeamEntity::getName, TEAM_NAME)
                .eq(AgentTeamEntity::getDeleted, 0));
        if (existing != null) return;
        List<Long> members = agents.subList(1, agents.size()).stream().map(AgentEntity::getId).toList();
        teamService.createTeam(WORKSPACE_ID, TEAM_NAME,
                "AI 动态发现、证据核验、公众号/小红书生产与审批交付团队。",
                agents.get(0).getId(), members, "ai-news-ops");
    }

    private void ensureCronJobs(Long leadAgentId, ChannelEntity notificationChannel) {
        boolean radarReady = radarReady();
        ensureCron(RADAR_CRON_NAME, "0 8 * * *",
                "每日按来源白名单检索全球与中国 AI 动态，调用 ai_news_event 写入候选事件；优先使用 feishu（飞书）渠道把摘要发送到已配置会话。只发现和核验线索，不生成或发表内容。",
                leadAgentId, radarReady, notificationChannel);
        ensureCron(WEEKLY_CRON_NAME, "0 9 * * 1",
                "从最近已核验且未生产的 AI 动态中选择主题，先在 IM/工作台请求确认；确认后使用 Team Run 并行生成公众号文章和小红书卡片，完成合规扫描后进入人工审批。未经确认不得交付。",
                leadAgentId, false, notificationChannel);
    }

    /** Compatibility overload used by pre-channel-binding callers. */
    private void ensureCronJobs(Long leadAgentId) {
        ensureCronJobs(leadAgentId, findNotificationChannel());
    }

    /**
     * A scheduled radar is useful only when its two external edges are
     * available. Keep the decision deterministic and environment-owned so a
     * fresh Docker deployment does not create a silently failing job.
     */
    private boolean radarReady() {
        if (!EnvironmentConfig.aiNewsRadarEnabled()) {
            return false;
        }
        boolean modelReady = EnvironmentConfig.configuredModelChain().stream()
                .anyMatch(selection -> EnvironmentConfig.providerApiKey(selection.providerId()) != null);
        if (!modelReady) {
            log.info("[AiNewsOps] AI news radar remains disabled: no usable .env model-chain credential");
            return false;
        }
        boolean domesticImReady = channelMapper.selectList(Wrappers.<ChannelEntity>lambdaQuery()
                        .eq(ChannelEntity::getWorkspaceId, WORKSPACE_ID)
                        .eq(ChannelEntity::getDeleted, 0))
                .stream()
                .anyMatch(channel -> Boolean.TRUE.equals(channel.getEnabled())
                        && DOMESTIC_IM_TYPES.contains(channel.getChannelType()));
        if (!domesticImReady) {
            log.info("[AiNewsOps] AI news radar remains disabled: no enabled domestic IM channel");
        }
        return domesticImReady;
    }

    private void ensureCron(String name, String expression, String requestBody,
                            Long agentId, boolean enabledByDefault,
                            ChannelEntity notificationChannel) {
        CronJobEntity existing = cronJobMapper.selectOne(Wrappers.<CronJobEntity>lambdaQuery()
                .eq(CronJobEntity::getWorkspaceId, WORKSPACE_ID)
                .eq(CronJobEntity::getName, name)
                .eq(CronJobEntity::getDeleted, 0));
        if (existing != null) {
            // Upgrade the idempotently seeded radar after credentials are
            // added. A temporary outage must not erase an operator's state;
            // only an explicit env opt-out disables it automatically.
            boolean explicitlyDisabled = RADAR_CRON_NAME.equals(name)
                    && !EnvironmentConfig.aiNewsRadarEnabled();
            if (RADAR_CRON_NAME.equals(name)
                    && enabledByDefault
                    && !Boolean.TRUE.equals(existing.getEnabled())) {
                existing.setEnabled(true);
                existing.setUpdateTime(LocalDateTime.now());
                cronJobMapper.updateById(existing);
            } else if (explicitlyDisabled && Boolean.TRUE.equals(existing.getEnabled())) {
                existing.setEnabled(false);
                existing.setUpdateTime(LocalDateTime.now());
                cronJobMapper.updateById(existing);
            }
            // A seeded job may have been created before the IM channel existed.
            // Bind it once a domestic channel is available, while preserving an
            // operator's explicit non-null binding.
            if (existing.getChannelId() == null && notificationChannel != null
                    && Boolean.TRUE.equals(notificationChannel.getEnabled())) {
                existing.setChannelId(notificationChannel.getId());
                existing.setDeliveryConfig(deliveryConfigFor(notificationChannel.getId()));
                existing.setUpdateTime(LocalDateTime.now());
                cronJobMapper.updateById(existing);
                log.info("[AiNewsOps] bound cron '{}' to channel={} targetBound={}", name,
                        notificationChannel.getId(), hasDeliveryTarget(existing.getDeliveryConfig()));
            }
            return;
        }
        CronJobEntity cron = new CronJobEntity();
        cron.setWorkspaceId(WORKSPACE_ID);
        cron.setName(name);
        cron.setCronExpression(expression);
        cron.setTimezone("Asia/Shanghai");
        cron.setAgentId(agentId);
        cron.setTaskType("agent");
        cron.setRequestBody(requestBody);
        cron.setEnabled(enabledByDefault);
        if (notificationChannel != null && Boolean.TRUE.equals(notificationChannel.getEnabled())) {
            cron.setChannelId(notificationChannel.getId());
            cron.setDeliveryConfig(deliveryConfigFor(notificationChannel.getId()));
        }
        cron.setCreateTime(LocalDateTime.now());
        cron.setUpdateTime(LocalDateTime.now());
        cron.setDeleted(0);
        cronJobMapper.insert(cron);
    }

    /** Prefer an enabled Feishu row, then another explicitly enabled domestic IM row. */
    private ChannelEntity findNotificationChannel() {
        List<ChannelEntity> channels = channelMapper.selectList(Wrappers.<ChannelEntity>lambdaQuery()
                .eq(ChannelEntity::getWorkspaceId, WORKSPACE_ID)
                .eq(ChannelEntity::getDeleted, 0));
        return channels.stream()
                .filter(channel -> Boolean.TRUE.equals(channel.getEnabled())
                        && DEFAULT_IM_TYPE.equalsIgnoreCase(channel.getChannelType()))
                .findFirst()
                .orElseGet(() -> channels.stream()
                        .filter(channel -> Boolean.TRUE.equals(channel.getEnabled())
                                && DOMESTIC_IM_TYPES.contains(channel.getChannelType()))
                        .findFirst().orElse(null));
    }

    /**
     * Capture a durable target when the operator has already chatted with the
     * bot. A fresh install has no session yet; the delivery strategy resolves
     * the first later session at execution time instead of disabling the cron.
     */
    private DeliveryConfig deliveryConfigFor(Long channelId) {
        if (channelId == null || channelSessionMapper == null) return null;
        ChannelSessionEntity session = channelSessionMapper.selectList(
                        Wrappers.<ChannelSessionEntity>lambdaQuery()
                                .eq(ChannelSessionEntity::getChannelId, channelId)
                                .orderByDesc(ChannelSessionEntity::getLastActiveTime)
                                .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (session == null || session.getTargetId() == null || session.getTargetId().isBlank()) {
            return null;
        }
        return DeliveryConfig.from(new ChannelTarget(session.getTargetId(), null, null),
                session.getSenderId());
    }

    private boolean hasDeliveryTarget(DeliveryConfig config) {
        return config != null && config.targetId() != null && !config.targetId().isBlank();
    }

    private void disableUnrelatedProductSurface() {
        disableLegacyAgents();
        Set<String> disabledSkills = Set.of(
                "news", "ckjia-shopping", "sql_query", "himalaya", "songwriting-and-ai-music",
                "digital_employee", "steve_jobs_perspective");
        for (SkillEntity skill : skillMapper.selectList(Wrappers.<SkillEntity>lambdaQuery()
                .in(SkillEntity::getName, disabledSkills))) {
            if (!Boolean.FALSE.equals(skill.getEnabled())) {
                skill.setEnabled(false);
                skill.setUpdateTime(LocalDateTime.now());
                skillMapper.updateById(skill);
            }
        }
        Set<String> disabledTools = Set.of(
                "VideoGenerateTool", "MusicGenerateTool", "TtsGenerateTool", "SttRecognizeTool",
                "Model3dGenerateTool", "sql_query", "datasource_query");
        for (ToolEntity tool : toolMapper.selectList(Wrappers.<ToolEntity>lambdaQuery()
                .in(ToolEntity::getName, disabledTools))) {
            if (!Boolean.FALSE.equals(tool.getEnabled())) {
                tool.setEnabled(false);
                tool.setUpdateTime(LocalDateTime.now());
                toolMapper.updateById(tool);
            }
        }
        disableLegacyCrons();
    }

    /** Hide legacy general-purpose/demo employees from the vertical product surface. */
    private void disableLegacyAgents() {
        Set<String> legacyNames = Set.of("通用助手", "任务规划师", "推理分析师", "内容工作室");
        for (AgentEntity agent : agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getWorkspaceId, WORKSPACE_ID)
                .in(AgentEntity::getName, legacyNames)
                .eq(AgentEntity::getDeleted, 0))) {
            if (Boolean.TRUE.equals(agent.getEnabled())) {
                agent.setEnabled(false);
                agent.setUpdateTime(LocalDateTime.now());
                agentMapper.updateById(agent);
                log.info("[AiNewsOps] disabled legacy agent '{}' ({})", agent.getName(), agent.getId());
            }
        }
    }

    /** Keep the vertical radar opt-in and remove legacy demo jobs from the scheduler. */
    private void disableLegacyCrons() {
        Set<String> legacyNames = Set.of(
                "每日问候", "每周工作总结", "每日选题雷达", "每周公众号入草稿箱",
                "Daily Greeting", "Weekly Work Summary", "Daily Topic Radar",
                "Weekly WeChat Draft");
        for (CronJobEntity cron : cronJobMapper.selectList(Wrappers.<CronJobEntity>lambdaQuery()
                .eq(CronJobEntity::getWorkspaceId, WORKSPACE_ID)
                .in(CronJobEntity::getName, legacyNames)
                .eq(CronJobEntity::getDeleted, 0))) {
            if (Boolean.TRUE.equals(cron.getEnabled())) {
                cron.setEnabled(false);
                cron.setUpdateTime(LocalDateTime.now());
                cronJobMapper.updateById(cron);
                log.info("[AiNewsOps] disabled legacy cron '{}' ({})", cron.getName(), cron.getId());
            }
        }
        // Older seeds could insert the memory-maintenance job more than once.
        // Keep one deterministic owner because memory consolidation is part of
        // the vertical product, but duplicate executions would double-write
        // reflections and distort Skill usage signals.
        List<CronJobEntity> memoryJobs = cronJobMapper.selectList(Wrappers.<CronJobEntity>lambdaQuery()
                .eq(CronJobEntity::getWorkspaceId, WORKSPACE_ID)
                .eq(CronJobEntity::getName, "记忆整合")
                .eq(CronJobEntity::getDeleted, 0)
                .orderByAsc(CronJobEntity::getId));
        for (int i = 1; i < memoryJobs.size(); i++) {
            CronJobEntity duplicate = memoryJobs.get(i);
            if (Boolean.TRUE.equals(duplicate.getEnabled())) {
                duplicate.setEnabled(false);
                duplicate.setUpdateTime(LocalDateTime.now());
                cronJobMapper.updateById(duplicate);
                log.info("[AiNewsOps] disabled duplicate memory cron ({})", duplicate.getId());
            }
        }
    }

    private static String promptFor(AgentSpec spec) {
        String base = "你是 NewsClaw AI 动态内容运营团队的" + spec.name() + "。"
                + "主线是追踪全球与中国 AI 模型、具身智能、机器人、芯片和大厂 AI 产品动态。"
                + "所有事实必须来自实际阅读的来源；官方来源优先，媒体只能补充；冲突就保留冲突，传闻标记未证实。"
                + "只有来源注册表中的可信媒体才贡献交叉核验；403 只代表抓取受阻，不能表述为官方未发布。"
                + "通过 ai_news_event 保存结构化事件和证据，不把未经核验的内容交给内容生产。"
                + "当当前请求来自飞书人工会话时，候选写入后调用 ai_news_review_card 并直接传事件 ID，让用户通过卡片决策。"
                + "遵守 workspace 隔离、人工审批和公众号草稿箱/小红书素材包边界。"
                + "你的职责是：" + spec.description();
        return switch (spec.tag()) {
            case "discover" -> base
                    + " 证据归档的完成定义是实际调用 wiki_create_page，把事件、来源 URL、sourceTier、claim、quote、置信度和冲突状态写入 AI 动态证据 Wiki，"
                    + "并随后调用 ai_news_event action=link_wiki 绑定 pageId。工具不可见时先 enable_tool(\"wiki_create_page\")；"
                    + "DOCX、普通文件或只给出页面建议不能替代 Wiki 页面。";
            case "edit" -> base
                    + " 公众号任务的完成定义是实际调用 gzh_package 并返回其在线预览 URL、素材 ZIP URL 和内容日历 item id。"
                    + "如果工具处于渐进目录，先调用 enable_tool(\"gzh_package\")；不得用 DOCX、普通文件或纯文本替代打包交付。"
                    + "内容仅能使用已核验 claims，必须保留来源边界；不得出现首个、唯一、最强、领先、顶级、全网、史上等绝对化措辞。";
            case "visual" -> base
                    + " 小红书任务必须先加载 xhs_note Skill，使用其 AI 科技媒体编辑模板生成至少 3 张真实、可访问的 3:4 竖图，"
                    + "按已确认事实、影响判断、后续观察拆卡；禁止整页渐变、大 emoji、装饰圆球和空泛 CTA，再实际调用 xhs_package。"
                    + "长模型名必须放在全宽流式正文或独占规格条，不能放在编号或窄标签列；来源标签只能来自已归档 Evidence Packet，"
                    + "无 x.com/twitter.com 归档证据时不得写官方 X/Twitter。"
                    + "如果工具处于渐进目录，先调用 enable_tool(\"xhs_package\")；必须返回预览 URL、素材 ZIP URL 和内容日历 item id。"
                    + "图片清单、设计说明或 DOCX 不是交付物。";
            case "delivery" -> base
                    + " 合规任务必须实际调用 compliance_scan，并核对 gzh_package/xhs_package 的返回结果。"
                    + "高危命中、缺少公众号素材包、或小红书少于 3 张图时必须阻断并说明原因。"
                    + "只在用户明确确认且凭证已配置后才允许 gzh_publish action=draft；绝不自动群发。";
            default -> base;
        };
    }

    private static boolean isLegacyVerticalPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) return true;
        return prompt.startsWith("你是 NewsClaw AI 动态内容运营团队的")
                && !prompt.contains("gzh_package")
                && !prompt.contains("xhs_package")
                && !prompt.contains("compliance_scan");
    }

    private record AgentSpec(String name, String description, String tag) {
    }
}

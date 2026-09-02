package vip.newsclaw.news.seed;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.newsclaw.agent.binding.model.AgentToolBinding;
import vip.newsclaw.agent.binding.model.AgentSkillBinding;
import vip.newsclaw.agent.binding.repository.AgentSkillBindingMapper;
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
import vip.newsclaw.news.service.AiNewsCandidatePipelineProperties;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the vertical AI-news operating team after the regular database seed.
 * It is intentionally idempotent. A fresh discovery radar is enabled only
 * when the candidate pipeline, deployment-owned model chain, and a domestic
 * IM target are ready; the weekly production job remains disabled because it
 * always requires user approval.
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
    private static final long LEGACY_TOPIC_RADAR_CRON_ID = 1000100020L;
    private static final String LEGACY_RADAR_REQUEST_BODY =
            "每日按来源白名单检索全球与中国 AI 动态，调用 ai_news_event 写入候选事件；"
                    + "优先使用 feishu（飞书）渠道把摘要发送到已配置会话。只发现和核验线索，不生成或发表内容。";
    private static final String RADAR_REQUEST_BODY =
            "每日按来源白名单检索全球与中国 AI 动态。先调用 ai_news_query（省略 scanRunId）读取"
                    + " candidatePipelineEnabled、最近 run、inProgress、fresh 标记和记分卡；"
                    + "latestRun.inProgress=true（RUNNING/CANDIDATES_PERSISTED/CAPTURE_PENDING 等）时等待后重查，"
                    + "不得重复扫描；仅当 candidatePipelineEnabled=true 且 latestRun 缺失/过期/失败时调用"
                    + " ai_news_scan，避免与每15分钟 scheduler 重复扫描，再按需调用 ai_news_review。"
                    + "candidate 结果不得当作 event/evidence；本定时任务 candidatePipelineEnabled=false 或工具"
                    + "不可用时必须记录 candidate_pipeline_disabled 并停止，禁止回退兼容 ai_news_event。"
                    + "优先使用 feishu（飞书）渠道把摘要发送到已配置会话。只发现和核验线索，不生成或发表内容。";
    private static final String BUILTIN_PROMPT_MARKER =
            "newsclaw-ai-news-prompt@2026.08.29-v6";
    /**
     * A Web/WebChat row is not an IM delivery target.  If any real domestic
     * IM row already exists, its owner has made an explicit channel choice and
     * the vertical seed must leave that choice untouched.
     */
    private static final Set<String> DOMESTIC_IM_TYPES = Set.of(
            "feishu", "dingtalk", "wecom", "weixin", "qq");
    /**
     * V213 exposes three candidate callbacks from one Spring bean.  Bind the
     * bean alias (rather than the DB display name {@code ai_news_pipeline}) so
     * AgentToolSet can resolve all callbacks at runtime. The legacy event tool
     * remains only as an explicit compatibility fallback; the candidate-to-event
     * promotion bridge is the preferred path and direct platform publishing is
     * disabled.
     */
    private static final String CANDIDATE_PIPELINE_TOOL = "aiNewsCandidateTool";
    private static final List<String> LEAD_REQUIRED_TOOLS = List.of(
            "ai_news_event", CANDIDATE_PIPELINE_TOOL, "ai_news_review_card",
            "TeamTasksTool", "ChannelMessageTool");
    private static final Set<String> LEGACY_LEAD_REQUIRED_TOOLS = Set.of(
            "ai_news_event", "ai_news_review_card", "TeamTasksTool", "ChannelMessageTool");
    private static final List<String> RADAR_REQUIRED_TOOLS = List.of(
            "web_search", "browser_use", "ai_news_event", CANDIDATE_PIPELINE_TOOL,
            "ai_news_review_card");
    private static final Set<String> RADAR_AGENT_NAMES = Set.of(
            "AI 动态主编", "热点发现员", "事实核查员");

    private final AgentMapper agentMapper;
    private final AgentSkillBindingMapper agentSkillBindingMapper;
    private final AgentToolBindingMapper agentToolBindingMapper;
    private final ChannelMapper channelMapper;
    private final ChannelSessionMapper channelSessionMapper;
    private final AgentTeamMapper teamMapper;
    private final TeamService teamService;
    private final CronJobMapper cronJobMapper;
    private final SkillMapper skillMapper;
    private final ToolMapper toolMapper;

    /** Spring-bound candidate flag; the environment fallback supports legacy
     * lightweight constructors used by migration/seed tests. */
    @Autowired(required = false)
    private AiNewsCandidatePipelineProperties candidatePipelineProperties;

    @Autowired
    public AiNewsOpsSeedRunner(AgentMapper agentMapper,
                               AgentSkillBindingMapper agentSkillBindingMapper,
                               AgentToolBindingMapper agentToolBindingMapper,
                               ChannelMapper channelMapper,
                               ChannelSessionMapper channelSessionMapper,
                               AgentTeamMapper teamMapper,
                               TeamService teamService,
                               CronJobMapper cronJobMapper,
                               SkillMapper skillMapper,
                               ToolMapper toolMapper) {
        this.agentMapper = agentMapper;
        this.agentSkillBindingMapper = agentSkillBindingMapper;
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
        this(agentMapper, null, null, channelMapper, channelSessionMapper, teamMapper, teamService,
                cronJobMapper, skillMapper, toolMapper);
    }

    /** Source-compatible constructor used by older callers with tool scopes. */
    public AiNewsOpsSeedRunner(AgentMapper agentMapper,
                               AgentToolBindingMapper agentToolBindingMapper,
                               ChannelMapper channelMapper,
                               ChannelSessionMapper channelSessionMapper,
                               AgentTeamMapper teamMapper,
                               TeamService teamService,
                               CronJobMapper cronJobMapper,
                               SkillMapper skillMapper,
                               ToolMapper toolMapper) {
        this(agentMapper, null, agentToolBindingMapper, channelMapper, channelSessionMapper,
                teamMapper, teamService, cronJobMapper, skillMapper, toolMapper);
    }

    /** Source-compatible constructor used by older tests and embedders. */
    public AiNewsOpsSeedRunner(AgentMapper agentMapper,
                               ChannelMapper channelMapper,
                               AgentTeamMapper teamMapper,
                               TeamService teamService,
                               CronJobMapper cronJobMapper,
                               SkillMapper skillMapper,
                               ToolMapper toolMapper) {
        this(agentMapper, null, null, channelMapper, null, teamMapper, teamService,
                cronJobMapper, skillMapper, toolMapper);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            disableUnrelatedProductSurface();
            List<AgentEntity> agents = ensureAgents();
            ensureLeadToolScope(agents.getFirst());
            ensureRadarAgentScopes(agents);
            ensureRadarSkillBindings(agents);
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
     * by its scheduled and interactive workflows. Existing custom scopes stay
     * untouched; the exact four-tool historical seed receives only the new
     * candidate callback bean.
     */
    private void ensureLeadToolScope(AgentEntity lead) {
        ensureToolScope(lead, LEAD_REQUIRED_TOOLS);
    }

    /**
     * Give the discovery/verification path the smallest read-only radar
     * surface when it has never been explicitly scoped. Existing rows are an
     * operator choice and are therefore left untouched.
     */
    private void ensureRadarAgentScopes(List<AgentEntity> agents) {
        if (agents == null) return;
        for (AgentEntity agent : agents) {
            if (agent != null && RADAR_AGENT_NAMES.contains(agent.getName())
                    && !"AI 动态主编".equals(agent.getName())) {
                ensureToolScope(agent, RADAR_REQUIRED_TOOLS);
            }
        }
    }

    private void ensureToolScope(AgentEntity agent, List<String> requiredTools) {
        if (agentToolBindingMapper == null || agent == null || agent.getId() == null
                || requiredTools == null || requiredTools.isEmpty()) {
            return;
        }
        List<AgentToolBinding> existing = agentToolBindingMapper.selectList(
                Wrappers.<AgentToolBinding>lambdaQuery()
                        .eq(AgentToolBinding::getAgentId, agent.getId())
                        .eq(AgentToolBinding::getDeleted, 0));
        if (existing != null && !existing.isEmpty()) {
            Set<String> enabledNames = existing.stream()
                    .filter(binding -> Boolean.TRUE.equals(binding.getEnabled()))
                    .map(AgentToolBinding::getToolName)
                    .collect(java.util.stream.Collectors.toSet());
            boolean exactLegacyLeadScope = "AI 动态主编".equals(agent.getName())
                    && existing.size() == LEGACY_LEAD_REQUIRED_TOOLS.size()
                    && enabledNames.equals(LEGACY_LEAD_REQUIRED_TOOLS);
            if (exactLegacyLeadScope) {
                insertToolBinding(agent.getId(), CANDIDATE_PIPELINE_TOOL, LocalDateTime.now());
                log.info("[AiNewsOps] upgraded legacy lead tool scope with candidate pipeline callbacks");
            }
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (String toolName : requiredTools) {
            insertToolBinding(agent.getId(), toolName, now);
        }
        log.info("[AiNewsOps] scoped agent '{}' ({}) to {} required tools",
                agent.getName(), agent.getId(), requiredTools.size());
    }

    private void insertToolBinding(Long agentId, String toolName, LocalDateTime now) {
        AgentToolBinding binding = new AgentToolBinding();
        binding.setAgentId(agentId);
        binding.setToolName(toolName);
        binding.setEnabled(true);
        binding.setCreateTime(now);
        binding.setUpdateTime(now);
        binding.setDeleted(0);
        agentToolBindingMapper.insert(binding);
    }

    /**
     * Bind the radar skill only for an agent that has no skill rows yet. This
     * preserves explicit operator allowlists (including an intentional empty
     * scope) while making a fresh vertical installation able to load the
     * workflow instructions.
     */
    private void ensureRadarSkillBindings(List<AgentEntity> agents) {
        if (agentSkillBindingMapper == null || skillMapper == null || agents == null) return;
        SkillEntity radar = skillMapper.selectOne(Wrappers.<SkillEntity>lambdaQuery()
                .eq(SkillEntity::getName, "ai_news_radar")
                .eq(SkillEntity::getDeleted, 0));
        if (radar == null || radar.getId() == null || !Boolean.TRUE.equals(radar.getEnabled())) {
            log.warn("[AiNewsOps] ai_news_radar skill is unavailable; vertical skill binding skipped");
            return;
        }
        for (AgentEntity agent : agents) {
            if (agent == null || agent.getId() == null || !RADAR_AGENT_NAMES.contains(agent.getName())) {
                continue;
            }
            List<AgentSkillBinding> existing = agentSkillBindingMapper.selectList(
                    Wrappers.<AgentSkillBinding>lambdaQuery()
                            .eq(AgentSkillBinding::getAgentId, agent.getId()));
            if (existing != null && !existing.isEmpty()) continue;
            AgentSkillBinding binding = new AgentSkillBinding();
            binding.setAgentId(agent.getId());
            binding.setSkillId(radar.getId());
            binding.setEnabled(true);
            binding.setCreateTime(LocalDateTime.now());
            binding.setUpdateTime(LocalDateTime.now());
            binding.setDeleted(0);
            agentSkillBindingMapper.insert(binding);
            log.info("[AiNewsOps] bound ai_news_radar to agent '{}' ({})",
                    agent.getName(), agent.getId());
        }
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
                if (isLegacyVerticalPrompt(agent.getSystemPrompt(), spec)) {
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
                RADAR_REQUEST_BODY,
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
        if (!candidatePipelineEnabled()) {
            log.info("[AiNewsOps] AI news radar remains disabled: candidate pipeline is not explicitly enabled; "
                    + "legacy ai_news_event discovery is manual-only");
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

    private boolean candidatePipelineEnabled() {
        return candidatePipelineProperties != null
                ? candidatePipelineProperties.isEnabled()
                : EnvironmentConfig.aiNewsCandidatePipelineEnabled();
    }

    private void ensureCron(String name, String expression, String requestBody,
                            Long agentId, boolean enabledByDefault,
                            ChannelEntity notificationChannel) {
        CronJobEntity existing = cronJobMapper.selectOne(Wrappers.<CronJobEntity>lambdaQuery()
                .eq(CronJobEntity::getWorkspaceId, WORKSPACE_ID)
                .eq(CronJobEntity::getName, name)
                .eq(CronJobEntity::getDeleted, 0));
        if (existing != null) {
            // Never auto-enable an existing row.  A disabled value may be an
            // operator's deliberate pause, and readiness can change on every
            // restart.  The seed only fails closed when the deployment says
            // the radar must not run; an operator can explicitly re-enable it
            // after the candidate pipeline is ready.
            boolean explicitlyDisabled = RADAR_CRON_NAME.equals(name)
                    && (!EnvironmentConfig.aiNewsRadarEnabled()
                    || !candidatePipelineEnabled());
            boolean changed = false;
            if (explicitlyDisabled && Boolean.TRUE.equals(existing.getEnabled())) {
                existing.setEnabled(false);
                changed = true;
            }
            if (RADAR_CRON_NAME.equals(name)
                    && (existing.getTriggerMessage() == null || existing.getTriggerMessage().isBlank())) {
                // Keep the managed identity out of the model-facing request
                // body; the inert triggerMessage field is metadata for gates.
                existing.setTriggerMessage(EnvironmentConfig.AI_NEWS_DAILY_RADAR_MARKER);
                changed = true;
            }
            if (RADAR_CRON_NAME.equals(name)
                    && LEGACY_RADAR_REQUEST_BODY.equals(existing.getRequestBody())) {
                existing.setRequestBody(RADAR_REQUEST_BODY);
                changed = true;
            }
            // A seeded job may have been created before the IM channel existed.
            // Bind it once a domestic channel is available, while preserving an
            // operator's explicit non-null binding.
            if (existing.getChannelId() == null && notificationChannel != null
                    && Boolean.TRUE.equals(notificationChannel.getEnabled())) {
                existing.setChannelId(notificationChannel.getId());
                existing.setDeliveryConfig(deliveryConfigFor(notificationChannel.getId()));
                changed = true;
                log.info("[AiNewsOps] bound cron '{}' to channel={} targetBound={}", name,
                        notificationChannel.getId(), hasDeliveryTarget(existing.getDeliveryConfig()));
            }
            if (changed) {
                existing.setUpdateTime(LocalDateTime.now());
                cronJobMapper.updateById(existing);
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
        if (RADAR_CRON_NAME.equals(name)) {
            cron.setTriggerMessage(EnvironmentConfig.AI_NEWS_DAILY_RADAR_MARKER);
        }
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
                "Daily Greeting", "Weekly Work Summary", "Daily Topic Radar", "Daily AI News Radar",
                "Weekly WeChat Draft");
        for (CronJobEntity cron : cronJobMapper.selectList(Wrappers.<CronJobEntity>lambdaQuery()
                .eq(CronJobEntity::getWorkspaceId, WORKSPACE_ID)
                .eq(CronJobEntity::getDeleted, 0))) {
            boolean legacyRadarBody = "agent".equalsIgnoreCase(cron.getTaskType())
                    && LEGACY_RADAR_REQUEST_BODY.equals(cron.getRequestBody());
            boolean knownLegacy = legacyNames.contains(cron.getName())
                    || Long.valueOf(LEGACY_TOPIC_RADAR_CRON_ID).equals(cron.getId())
                    || legacyRadarBody;
            if (!knownLegacy) continue;
            boolean changed = false;
            if (legacyRadarBody) {
                cron.setRequestBody(RADAR_REQUEST_BODY);
                changed = true;
            }
            if (("每日选题雷达".equals(cron.getName())
                    || "Daily Topic Radar".equals(cron.getName())
                    || "Daily AI News Radar".equals(cron.getName())
                    || Long.valueOf(LEGACY_TOPIC_RADAR_CRON_ID).equals(cron.getId())
                    || legacyRadarBody)
                    && (cron.getTriggerMessage() == null || cron.getTriggerMessage().isBlank())) {
                cron.setTriggerMessage(EnvironmentConfig.AI_NEWS_DAILY_RADAR_MARKER);
                changed = true;
            }
            if (Boolean.TRUE.equals(cron.getEnabled())) {
                cron.setEnabled(false);
                changed = true;
            }
            if (changed) {
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
        String base = "[" + BUILTIN_PROMPT_MARKER + ":" + spec.tag() + "] "
                + "你是 NewsClaw AI 动态内容运营团队的" + spec.name() + "。"
                + "主线是追踪全球与中国 AI 模型、具身智能、机器人、芯片和大厂 AI 产品动态。"
                + "所有事实必须来自实际阅读的来源；搜索摘要只是线索，403 只代表抓取受阻，不能表述为官方未发布。"
                + "发现任务必须二选一且不能混用：候选分支是优先路径；先调用 ai_news_query（省略 scanRunId）查看 candidatePipelineEnabled、latestRun、inProgress、fresh 标记和记分卡；latestRun.inProgress=true（RUNNING/CANDIDATES_PERSISTED/CAPTURE_PENDING 等）时等待后重查，不得重复扫描；仅当 candidatePipelineEnabled=true 且 latestRun 缺失/过期/失败时才调用 ai_news_scan，避免与 scheduler 重复扫描，再用 ai_news_query 分页查看候选，人工决定时调用 ai_news_review。candidateId 不能当 eventId；该分支只管理候选和抓取，不能声称已核验或发布。只有 selected、人工 ACCEPTED 且 capture SUCCESS 的候选，才可用 ai_news_promote 提交原子 claim、逐字 quote 和语义关系，显式创建 candidate 状态事件；promotion 不核验、不发布。条件不足或入口不可用时立即报告阻断，不要再调用兼容事件 discover/capture/upsert 或重复计数。"
                + "仅当 candidatePipelineEnabled=false，或 ai_news_scan 返回未启用/不可用时，才记录 candidate_pipeline_fallback 原因并进入兼容分支：在该分支冻结窗口，调用兼容 ai_news_event(action=discover) 做五条分组官方检索与五条垂类新闻检索（含可信媒体限定通道），合并部署方配置的零 Web 搜索额度 RSS/Atom 结构化候选，再做 RRF 融合和缺口补检索。"
                + "候选 publishedAtHint（包括 feed 时间）仅用于筛选，明显越界者后置；时效和主题相关性优先于来源等级，不能让旧官方常驻页挤掉当前媒体新闻；有抓取上限时先覆盖带窗口内结构化时间且标题明确为新闻动作的候选，再把剩余额度用于无时间提示的官方页，最终时间仍只认 capture。"
                + "兼容分支终答前调用 ai_news_event action=window_summary；候选分支只引用 ai_news_query 的后端记分卡。两种分支都不得自行估算数量和状态。"
                + "对每条 Evidence 只判断 quote 对完整 claim 的 semanticRelation：entails、contradicts、partial、unrelated 或 hedged，"
                + "并给出 relationConfidence。来源声誉、证据数量、是否想通过核验或是否要拒绝都不能改变这项逐条语义判断；"
                + "不同产品、文档、受众、功能限定或时间点默认不是冲突，不确定时不得伪装成 entails。"
                + "来源等级、独立媒体计数、高风险、可信冲突、核验资格、允许引用 ID 和人工复核由后端按 URL 注册表确定性计算，"
                + "不得由模型或 publisher 文本覆盖；Evidence Packet 外、改写或重复的引用 ID 一律不可用。"
                + "严格 upsert 只接受一条不超过 512 字符的原子 claim，卡片标题摘要由 claim 派生；模型 relation 不能单独通过上线核验，须人工复核或 claim 与 quote 完全相同。"
                + "在兼容分支通过 ai_news_event 保存结构化事件、claim、quote、semanticRelation 和置信度；定时任务或 Agent 不得调用 mark_verified，只有可归因的人工上下文才能完成核验。"
                + "unknown、partial、hedged、unrelated、可信冲突或证据不足时不得把内容交给生产。"
                + "当当前请求来自飞书人工会话时，只有兼容事件分支在 ai_news_event upsert 成功并拿到真实 eventId 后，才调用 ai_news_review_card 让用户通过卡片决策；候选分支没有 eventId 时只能调用 ai_news_review，不能把 candidateId 传给 review_card，并须明确报告尚未形成事件卡片。"
                + "遵守 workspace 隔离、人工审批和公众号草稿箱/小红书素材包边界。"
                + "你的职责是：" + spec.description();
        return switch (spec.tag()) {
            case "discover" -> base
                    + " 证据归档的完成定义是实际调用 wiki_create_page，把事件、来源 URL、后端来源等级、claim、quote、semanticRelation、置信度和冲突状态写入 AI 动态证据 Wiki，"
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

    private static boolean isLegacyVerticalPrompt(String prompt, AgentSpec spec) {
        if (prompt == null || prompt.isBlank()) return true;
        if (prompt.contains("newsclaw-ai-news-prompt@")) {
            String marker = BUILTIN_PROMPT_MARKER + ":" + spec.tag() + "]";
            return !prompt.contains(marker);
        }
        return prompt.startsWith("你是 NewsClaw AI 动态内容运营团队的")
                && !prompt.contains("gzh_package")
                && !prompt.contains("xhs_package")
                && !prompt.contains("compliance_scan");
    }

    private record AgentSpec(String name, String description, String tag) {
    }
}

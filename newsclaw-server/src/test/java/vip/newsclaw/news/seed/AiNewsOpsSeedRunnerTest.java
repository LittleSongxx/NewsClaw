package vip.newsclaw.news.seed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.agent.binding.model.AgentSkillBinding;
import vip.newsclaw.agent.binding.model.AgentToolBinding;
import vip.newsclaw.agent.binding.repository.AgentSkillBindingMapper;
import vip.newsclaw.agent.binding.repository.AgentToolBindingMapper;
import vip.newsclaw.channel.model.ChannelEntity;
import vip.newsclaw.channel.repository.ChannelMapper;
import vip.newsclaw.cron.repository.CronJobMapper;
import vip.newsclaw.config.EnvironmentConfig;
import vip.newsclaw.skill.repository.SkillMapper;
import vip.newsclaw.team.repository.AgentTeamMapper;
import vip.newsclaw.team.service.TeamService;
import vip.newsclaw.tool.repository.ToolMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiNewsOpsSeedRunnerTest {

    @Mock
    private AgentMapper agentMapper;
    @Mock
    private AgentSkillBindingMapper agentSkillBindingMapper;
    @Mock
    private AgentToolBindingMapper agentToolBindingMapper;
    @Mock
    private ChannelMapper channelMapper;
    @Mock
    private AgentTeamMapper teamMapper;
    @Mock
    private TeamService teamService;
    @Mock
    private CronJobMapper cronJobMapper;
    @Mock
    private SkillMapper skillMapper;
    @Mock
    private ToolMapper toolMapper;

    private AiNewsOpsSeedRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AiNewsOpsSeedRunner(agentMapper, channelMapper, teamMapper, teamService,
                cronJobMapper, skillMapper, toolMapper);
        System.setProperty("NEWSCLAW_ENV_CONFIG_ENABLED", "true");
        System.setProperty("FEISHU_APP_ID", "test-feishu-app");
        System.setProperty("FEISHU_APP_SECRET", "test-feishu-secret");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("NEWSCLAW_ENV_CONFIG_ENABLED");
        System.clearProperty("FEISHU_APP_ID");
        System.clearProperty("FEISHU_APP_SECRET");
        System.clearProperty("NEWSCLAW_CHANNEL_FEISHU_APP_ID");
        System.clearProperty("NEWSCLAW_CHANNEL_FEISHU_APP_SECRET");
        System.clearProperty("NEWSCLAW_PRIMARY_MODEL_PROVIDER");
        System.clearProperty("NEWSCLAW_PRIMARY_MODEL");
        System.clearProperty("NEWSCLAW_FALLBACK_MODEL_CHAIN");
        System.clearProperty("BAILIAN_API_KEY");
        System.clearProperty("NEWSCLAW_AI_NEWS_RADAR_ENABLED");
        System.clearProperty("NEWSCLAW_AI_NEWS_CANDIDATE_PIPELINE_ENABLED");
    }

    @Test
    void seedsFeishuOnlyWhenWorkspaceHasNoDomesticIm() {
        when(channelMapper.selectList(any())).thenReturn(List.of());

        invokeEnsureDefaultFeishuChannel(11L);

        ArgumentCaptor<ChannelEntity> captor = ArgumentCaptor.forClass(ChannelEntity.class);
        verify(channelMapper).insert(captor.capture());
        ChannelEntity created = captor.getValue();
        assertEquals("feishu", created.getChannelType());
        assertEquals("AI 动态默认飞书", created.getName());
        assertEquals(11L, created.getAgentId());
        assertEquals(1L, created.getWorkspaceId());
        assertEquals("{\"connection_mode\":\"websocket\",\"domain\":\"feishu\"}",
                created.getConfigJson());
        assertFalse(created.getConfigJson().contains("test-feishu-secret"));
        assertFalse(created.getConfigJson().contains("app_secret"));
    }

    @Test
    void respectsExistingDomesticImAndDoesNotReplaceIt() {
        ChannelEntity existing = new ChannelEntity();
        existing.setName("已有钉钉");
        existing.setChannelType("dingtalk");
        when(channelMapper.selectList(any())).thenReturn(List.of(existing));

        invokeEnsureDefaultFeishuChannel(11L);

        verify(channelMapper, never()).insert(any(ChannelEntity.class));
    }

    @Test
    void repeatedBootstrapIsIdempotent() {
        List<ChannelEntity> rows = new ArrayList<>();
        when(channelMapper.selectList(any())).thenReturn(rows);
        doAnswer(invocation -> {
            ChannelEntity row = invocation.getArgument(0);
            row.setId(901L);
            rows.add(row);
            return 1;
        }).when(channelMapper).insert(any(ChannelEntity.class));

        invokeEnsureDefaultFeishuChannel(11L);
        invokeEnsureDefaultFeishuChannel(11L);

        verify(channelMapper).insert(any(ChannelEntity.class));
        assertEquals(1, rows.size());
    }

    @Test
    void missingBootstrapCredentialsLeaveChannelTableUntouched() {
        System.clearProperty("FEISHU_APP_ID");
        System.clearProperty("FEISHU_APP_SECRET");

        invokeEnsureDefaultFeishuChannel(11L);

        verify(channelMapper, never()).selectList(any());
        verify(channelMapper, never()).insert(any(ChannelEntity.class));
    }

    @Test
    void seededConfigContainsTransportDefaultsOnly() {
        when(channelMapper.selectList(any())).thenReturn(List.of());

        invokeEnsureDefaultFeishuChannel(11L);

        ArgumentCaptor<ChannelEntity> captor = ArgumentCaptor.forClass(ChannelEntity.class);
        verify(channelMapper).insert(captor.capture());
        String config = captor.getValue().getConfigJson();
        assertTrue(config.contains("connection_mode"));
        assertFalse(config.contains("test-feishu-app"));
        assertFalse(config.contains("test-feishu-secret"));
    }

    @Test
    void enablesDailyRadarOnlyWhenModelChainAndDomesticImAreReady() {
        System.setProperty("NEWSCLAW_PRIMARY_MODEL_PROVIDER", "bailian-team");
        System.setProperty("NEWSCLAW_PRIMARY_MODEL", "qwen3.7-plus");
        System.setProperty("BAILIAN_API_KEY", "test-bailian-key");
        System.setProperty("NEWSCLAW_AI_NEWS_CANDIDATE_PIPELINE_ENABLED", "true");

        ChannelEntity feishu = new ChannelEntity();
        feishu.setChannelType("feishu");
        feishu.setEnabled(true);
        when(channelMapper.selectList(any())).thenReturn(List.of(feishu));
        when(cronJobMapper.selectOne(any())).thenReturn(null);

        ReflectionTestUtils.invokeMethod(runner, "ensureCronJobs", 11L);

        ArgumentCaptor<vip.newsclaw.cron.model.CronJobEntity> captor =
                ArgumentCaptor.forClass(vip.newsclaw.cron.model.CronJobEntity.class);
        verify(cronJobMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<vip.newsclaw.cron.model.CronJobEntity> jobs = captor.getAllValues();
        vip.newsclaw.cron.model.CronJobEntity radar = jobs.stream()
                .filter(job -> "每日 AI 动态雷达".equals(job.getName()))
                .findFirst().orElseThrow();
        assertTrue(radar.getEnabled());
        assertEquals(EnvironmentConfig.AI_NEWS_DAILY_RADAR_MARKER, radar.getTriggerMessage());
        assertFalse(jobs.stream().filter(job -> "AI 动态周报生产".equals(job.getName()))
                .findFirst().orElseThrow().getEnabled());
    }

    @Test
    void keepsRadarDisabledWhenModelCredentialIsMissing() {
        System.setProperty("NEWSCLAW_PRIMARY_MODEL_PROVIDER", "bailian-team");
        System.setProperty("NEWSCLAW_PRIMARY_MODEL", "qwen3.7-plus");
        System.setProperty("NEWSCLAW_AI_NEWS_CANDIDATE_PIPELINE_ENABLED", "true");
        ChannelEntity feishu = new ChannelEntity();
        feishu.setChannelType("feishu");
        feishu.setEnabled(true);
        when(cronJobMapper.selectOne(any())).thenReturn(null);

        ReflectionTestUtils.invokeMethod(runner, "ensureCronJobs", 11L);

        ArgumentCaptor<vip.newsclaw.cron.model.CronJobEntity> captor =
                ArgumentCaptor.forClass(vip.newsclaw.cron.model.CronJobEntity.class);
        verify(cronJobMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertFalse(captor.getAllValues().stream()
                .filter(job -> "每日 AI 动态雷达".equals(job.getName()))
                .findFirst().orElseThrow().getEnabled());
    }

    @Test
    void candidateFlagKeepsRadarDisabledEvenWhenModelAndChannelAreReady() {
        System.setProperty("NEWSCLAW_PRIMARY_MODEL_PROVIDER", "bailian-team");
        System.setProperty("NEWSCLAW_PRIMARY_MODEL", "qwen3.7-plus");
        System.setProperty("BAILIAN_API_KEY", "test-bailian-key");
        System.setProperty("NEWSCLAW_AI_NEWS_CANDIDATE_PIPELINE_ENABLED", "false");

        ChannelEntity feishu = new ChannelEntity();
        feishu.setChannelType("feishu");
        feishu.setEnabled(true);
        when(channelMapper.selectList(any())).thenReturn(List.of(feishu));
        when(cronJobMapper.selectOne(any())).thenReturn(null);

        ReflectionTestUtils.invokeMethod(runner, "ensureCronJobs", 11L);

        ArgumentCaptor<vip.newsclaw.cron.model.CronJobEntity> captor =
                ArgumentCaptor.forClass(vip.newsclaw.cron.model.CronJobEntity.class);
        verify(cronJobMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertFalse(captor.getAllValues().stream()
                .filter(job -> "每日 AI 动态雷达".equals(job.getName()))
                .findFirst().orElseThrow().getEnabled());
    }

    @Test
    void upgradesOnlyTheKnownLegacyRadarRequestBody() {
        System.setProperty("NEWSCLAW_PRIMARY_MODEL_PROVIDER", "bailian-team");
        System.setProperty("NEWSCLAW_PRIMARY_MODEL", "qwen3.7-plus");
        System.setProperty("BAILIAN_API_KEY", "test-bailian-key");
        System.setProperty("NEWSCLAW_AI_NEWS_CANDIDATE_PIPELINE_ENABLED", "true");
        ChannelEntity feishu = new ChannelEntity();
        feishu.setChannelType("feishu");
        feishu.setEnabled(true);
        when(channelMapper.selectList(any())).thenReturn(List.of(feishu));

        vip.newsclaw.cron.model.CronJobEntity legacy = new vip.newsclaw.cron.model.CronJobEntity();
        legacy.setName("每日 AI 动态雷达");
        legacy.setEnabled(true);
        legacy.setRequestBody("每日按来源白名单检索全球与中国 AI 动态，调用 ai_news_event 写入候选事件；"
                + "优先使用 feishu（飞书）渠道把摘要发送到已配置会话。只发现和核验线索，不生成或发表内容。");
        when(cronJobMapper.selectOne(any())).thenReturn(legacy, null);

        ReflectionTestUtils.invokeMethod(runner, "ensureCronJobs", 11L);

        ArgumentCaptor<vip.newsclaw.cron.model.CronJobEntity> updated =
                ArgumentCaptor.forClass(vip.newsclaw.cron.model.CronJobEntity.class);
        verify(cronJobMapper).updateById(updated.capture());
        assertTrue(updated.getValue().getRequestBody().contains("ai_news_query"));
        assertTrue(updated.getValue().getRequestBody().contains("禁止回退兼容 ai_news_event"));

        org.mockito.Mockito.reset(cronJobMapper);
        vip.newsclaw.cron.model.CronJobEntity customized = new vip.newsclaw.cron.model.CronJobEntity();
        customized.setName("每日 AI 动态雷达");
        customized.setEnabled(false);
        customized.setRequestBody("运营自定义日报任务");
        when(cronJobMapper.selectOne(any())).thenReturn(customized, null);

        ReflectionTestUtils.invokeMethod(runner, "ensureCronJobs", 11L);

        verify(cronJobMapper).updateById(updated.capture());
        assertEquals("运营自定义日报任务", updated.getValue().getRequestBody());
    }

    @Test
    void disablesRenamedHistoricalDirectEventRadar() {
        vip.newsclaw.cron.model.CronJobEntity renamed = new vip.newsclaw.cron.model.CronJobEntity();
        renamed.setId(900L);
        renamed.setName("运营改过名的日报");
        renamed.setTaskType("agent");
        renamed.setRequestBody("每日按来源白名单检索全球与中国 AI 动态，调用 ai_news_event 写入候选事件；"
                + "优先使用 feishu（飞书）渠道把摘要发送到已配置会话。只发现和核验线索，不生成或发表内容。");
        renamed.setEnabled(true);
        when(cronJobMapper.selectList(any())).thenReturn(List.of(renamed));

        ReflectionTestUtils.invokeMethod(runner, "disableLegacyCrons");

        assertFalse(renamed.getEnabled());
        assertEquals(EnvironmentConfig.AI_NEWS_DAILY_RADAR_MARKER, renamed.getTriggerMessage());
        assertTrue(renamed.getRequestBody().contains("ai_news_query"));
        assertTrue(renamed.getRequestBody().contains("禁止回退兼容 ai_news_event"));
        verify(cronJobMapper).updateById(renamed);
    }

    @Test
    void upgradesKnownLegacyVerticalPromptButLeavesOperatorPromptUntouched() {
        vip.newsclaw.agent.model.AgentEntity legacy = new vip.newsclaw.agent.model.AgentEntity();
        legacy.setName("内容编辑");
        legacy.setEnabled(true);
        legacy.setSystemPrompt("你是 NewsClaw AI 动态内容运营团队的内容编辑。主线是追踪全球与中国 AI 模型。");
        when(agentMapper.selectOne(any())).thenReturn(legacy);

        List<String> updatedPrompts = new ArrayList<>();
        doAnswer(invocation -> {
            vip.newsclaw.agent.model.AgentEntity updated = invocation.getArgument(0);
            updatedPrompts.add(updated.getSystemPrompt());
            return 1;
        }).when(agentMapper).updateById(any(vip.newsclaw.agent.model.AgentEntity.class));
        invokeEnsureAgents();

        verify(agentMapper, org.mockito.Mockito.atLeastOnce())
                .updateById(any(vip.newsclaw.agent.model.AgentEntity.class));
        assertTrue(updatedPrompts.stream().anyMatch(prompt -> prompt.contains("gzh_package")));
        assertTrue(updatedPrompts.stream().anyMatch(prompt -> prompt.contains("semanticRelation")));
        assertTrue(updatedPrompts.stream().anyMatch(prompt -> prompt.contains("由后端")));
        assertTrue(updatedPrompts.stream().noneMatch(prompt -> prompt.contains("官方 host=openai.com")));
        assertTrue(updatedPrompts.stream().anyMatch(prompt -> prompt.contains("newsclaw-ai-news-prompt@2026.08.29-v6")));
        assertTrue(updatedPrompts.stream().anyMatch(prompt -> prompt.contains("ai_news_scan")));
        assertTrue(updatedPrompts.stream().anyMatch(prompt -> prompt.contains("latestRun")));
        assertTrue(updatedPrompts.stream().anyMatch(prompt -> prompt.contains("inProgress")));
        assertTrue(updatedPrompts.stream().anyMatch(prompt -> prompt.contains("fresh")));
        assertTrue(updatedPrompts.stream().anyMatch(prompt -> prompt.contains("兼容 ai_news_event(action=discover)")));

        org.mockito.Mockito.reset(agentMapper);
        vip.newsclaw.agent.model.AgentEntity customized = new vip.newsclaw.agent.model.AgentEntity();
        customized.setName("内容编辑");
        customized.setEnabled(true);
        customized.setSystemPrompt("运营团队自定义提示：只用于内部试验。");
        when(agentMapper.selectOne(any())).thenReturn(customized);

        invokeEnsureAgents();

        verify(agentMapper, never()).updateById(any(vip.newsclaw.agent.model.AgentEntity.class));
    }

    @Test
    void scopesLeadOnlyWhenNoOperatorToolBindingExists() {
        vip.newsclaw.agent.model.AgentEntity lead = new vip.newsclaw.agent.model.AgentEntity();
        lead.setId(99L);
        lead.setName("AI 动态主编");

        when(agentToolBindingMapper.selectList(any())).thenReturn(List.of());
        AiNewsOpsSeedRunner scopedRunner = new AiNewsOpsSeedRunner(agentMapper, agentToolBindingMapper,
                channelMapper, null, teamMapper, teamService, cronJobMapper, skillMapper, toolMapper);

        ReflectionTestUtils.invokeMethod(scopedRunner, "ensureLeadToolScope", lead);

        ArgumentCaptor<AgentToolBinding> toolCaptor = ArgumentCaptor.forClass(AgentToolBinding.class);
        verify(agentToolBindingMapper, org.mockito.Mockito.times(5)).insert(toolCaptor.capture());
        assertTrue(toolCaptor.getAllValues().stream()
                .anyMatch(binding -> "aiNewsCandidateTool".equals(binding.getToolName())));

        org.mockito.Mockito.reset(agentToolBindingMapper);
        List<AgentToolBinding> legacyScope = List.of(
                toolBinding("ai_news_event"), toolBinding("ai_news_review_card"),
                toolBinding("TeamTasksTool"), toolBinding("ChannelMessageTool"));
        when(agentToolBindingMapper.selectList(any())).thenReturn(legacyScope);
        ReflectionTestUtils.invokeMethod(scopedRunner, "ensureLeadToolScope", lead);
        verify(agentToolBindingMapper).insert(toolCaptor.capture());
        assertEquals("aiNewsCandidateTool", toolCaptor.getValue().getToolName());

        org.mockito.Mockito.reset(agentToolBindingMapper);
        AgentToolBinding existing = new AgentToolBinding();
        existing.setAgentId(99L);
        existing.setToolName("ai_news_event");
        when(agentToolBindingMapper.selectList(any())).thenReturn(List.of(existing));
        ReflectionTestUtils.invokeMethod(scopedRunner, "ensureLeadToolScope", lead);
        verify(agentToolBindingMapper, never()).insert(any(AgentToolBinding.class));
    }

    @Test
    void seedsRadarScopeForUnscopedVerifierAndPreservesExistingSkillBindings() {
        vip.newsclaw.agent.model.AgentEntity verifier = new vip.newsclaw.agent.model.AgentEntity();
        verifier.setId(101L);
        verifier.setName("事实核查员");
        vip.newsclaw.skill.model.SkillEntity radar = new vip.newsclaw.skill.model.SkillEntity();
        radar.setId(202L);
        radar.setName("ai_news_radar");
        radar.setEnabled(true);

        when(agentToolBindingMapper.selectList(any())).thenReturn(List.of());
        when(agentSkillBindingMapper.selectList(any())).thenReturn(List.of());
        when(skillMapper.selectOne(any())).thenReturn(radar);

        AiNewsOpsSeedRunner scopedRunner = new AiNewsOpsSeedRunner(agentMapper,
                agentSkillBindingMapper, agentToolBindingMapper, channelMapper, null,
                teamMapper, teamService, cronJobMapper, skillMapper, toolMapper);
        ReflectionTestUtils.invokeMethod(scopedRunner, "ensureRadarAgentScopes", List.of(verifier));
        ReflectionTestUtils.invokeMethod(scopedRunner, "ensureRadarSkillBindings", List.of(verifier));

        ArgumentCaptor<AgentToolBinding> radarToolCaptor = ArgumentCaptor.forClass(AgentToolBinding.class);
        verify(agentToolBindingMapper, org.mockito.Mockito.times(5)).insert(radarToolCaptor.capture());
        assertTrue(radarToolCaptor.getAllValues().stream()
                .anyMatch(binding -> "aiNewsCandidateTool".equals(binding.getToolName())));
        ArgumentCaptor<AgentSkillBinding> skillCaptor = ArgumentCaptor.forClass(AgentSkillBinding.class);
        verify(agentSkillBindingMapper).insert(skillCaptor.capture());
        assertEquals(101L, skillCaptor.getValue().getAgentId());
        assertEquals(202L, skillCaptor.getValue().getSkillId());

        org.mockito.Mockito.reset(agentSkillBindingMapper);
        AgentSkillBinding existing = new AgentSkillBinding();
        existing.setAgentId(101L);
        existing.setSkillId(999L);
        when(agentSkillBindingMapper.selectList(any())).thenReturn(List.of(existing));
        ReflectionTestUtils.invokeMethod(scopedRunner, "ensureRadarSkillBindings", List.of(verifier));
        verify(agentSkillBindingMapper, never()).insert(any(AgentSkillBinding.class));
    }

    private static AgentToolBinding toolBinding(String name) {
        AgentToolBinding binding = new AgentToolBinding();
        binding.setToolName(name);
        binding.setEnabled(true);
        binding.setDeleted(0);
        return binding;
    }

    private void invokeEnsureDefaultFeishuChannel(Long leadAgentId) {
        ReflectionTestUtils.invokeMethod(runner, "ensureDefaultFeishuChannel", leadAgentId);
    }

    @SuppressWarnings("unchecked")
    private void invokeEnsureAgents() {
        ReflectionTestUtils.invokeMethod(runner, "ensureAgents");
    }
}

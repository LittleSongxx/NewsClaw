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
import vip.newsclaw.channel.model.ChannelEntity;
import vip.newsclaw.channel.repository.ChannelMapper;
import vip.newsclaw.cron.repository.CronJobMapper;
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
        assertTrue(jobs.stream().filter(job -> "每日 AI 动态雷达".equals(job.getName()))
                .findFirst().orElseThrow().getEnabled());
        assertFalse(jobs.stream().filter(job -> "AI 动态周报生产".equals(job.getName()))
                .findFirst().orElseThrow().getEnabled());
    }

    @Test
    void keepsRadarDisabledWhenModelCredentialIsMissing() {
        System.setProperty("NEWSCLAW_PRIMARY_MODEL_PROVIDER", "bailian-team");
        System.setProperty("NEWSCLAW_PRIMARY_MODEL", "qwen3.7-plus");
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
    void upgradesKnownLegacyVerticalPromptButLeavesOperatorPromptUntouched() {
        vip.newsclaw.agent.model.AgentEntity legacy = new vip.newsclaw.agent.model.AgentEntity();
        legacy.setName("内容编辑");
        legacy.setEnabled(true);
        legacy.setSystemPrompt("你是 NewsClaw AI 动态内容运营团队的内容编辑。主线是追踪全球与中国 AI 模型。");
        when(agentMapper.selectOne(any())).thenReturn(legacy);

        invokeEnsureAgents();

        ArgumentCaptor<vip.newsclaw.agent.model.AgentEntity> upgraded =
                ArgumentCaptor.forClass(vip.newsclaw.agent.model.AgentEntity.class);
        verify(agentMapper, org.mockito.Mockito.atLeastOnce()).updateById(upgraded.capture());
        assertTrue(upgraded.getAllValues().stream()
                .anyMatch(agent -> agent.getSystemPrompt().contains("gzh_package")));

        org.mockito.Mockito.reset(agentMapper);
        vip.newsclaw.agent.model.AgentEntity customized = new vip.newsclaw.agent.model.AgentEntity();
        customized.setName("内容编辑");
        customized.setEnabled(true);
        customized.setSystemPrompt("运营团队自定义提示：只用于内部试验。");
        when(agentMapper.selectOne(any())).thenReturn(customized);

        invokeEnsureAgents();

        verify(agentMapper, never()).updateById(any(vip.newsclaw.agent.model.AgentEntity.class));
    }

    private void invokeEnsureDefaultFeishuChannel(Long leadAgentId) {
        ReflectionTestUtils.invokeMethod(runner, "ensureDefaultFeishuChannel", leadAgentId);
    }

    @SuppressWarnings("unchecked")
    private void invokeEnsureAgents() {
        ReflectionTestUtils.invokeMethod(runner, "ensureAgents");
    }
}

package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.team.event.TeamRunDispatchCommittedIntent;
import vip.newsclaw.team.model.AgentTeamEntity;
import vip.newsclaw.team.model.AgentTeamMemberEntity;
import vip.newsclaw.team.model.TeamRole;
import vip.newsclaw.team.model.TeamRunCreateCommand;
import vip.newsclaw.team.model.TeamRunEntity;
import vip.newsclaw.team.model.TeamTaskCreateCommand;
import vip.newsclaw.team.model.TeamTaskEntity;
import vip.newsclaw.team.repository.AgentTeamMapper;
import vip.newsclaw.team.service.TeamRunService;
import vip.newsclaw.team.service.TeamService;
import vip.newsclaw.team.service.TeamTaskService;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsProductionServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long EVENT_ID = 101L;
    private static final long TEAM_ID = 20L;
    private static final long RUN_ID = 30L;
    private static final long LEAD_ID = 40L;

    private AgentTeamMapper teamMapper;
    private TeamService teamService;
    private AgentMapper agentMapper;
    private TeamRunService runService;
    private TeamTaskService taskService;
    private AiNewsEventService eventService;
    private ApplicationEventPublisher eventPublisher;
    private AiNewsProductionService service;

    @BeforeEach
    void setUp() {
        teamMapper = mock(AgentTeamMapper.class);
        teamService = mock(TeamService.class);
        agentMapper = mock(AgentMapper.class);
        runService = mock(TeamRunService.class);
        taskService = mock(TeamTaskService.class);
        eventService = mock(AiNewsEventService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new AiNewsProductionService(teamMapper, teamService, agentMapper, runService,
                taskService, eventService, eventPublisher);
    }

    @Test
    void onlyInProductionEventsCanStartATeamRun() {
        when(eventService.findEvent(WORKSPACE_ID, EVENT_ID)).thenReturn(event("verified", null));

        assertThrows(NewsClawException.class, () -> service.start(WORKSPACE_ID, EVENT_ID));

        verify(runService, never()).startRun(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void anExistingRunMakesStartIdempotent() {
        AiNewsEventEntity existing = event("in_production", RUN_ID);
        when(eventService.findEvent(WORKSPACE_ID, EVENT_ID)).thenReturn(existing);

        assertEquals(existing, service.start(WORKSPACE_ID, EVENT_ID));

        verify(teamMapper, never()).selectOne(any());
        verify(runService, never()).startRun(any());
        verify(taskService, never()).createTask(any());
    }

    @Test
    void createsDurableEditorialDagLinksEventAndDispatchesAfterCommitIntent() throws Exception {
        AiNewsEventEntity input = event("in_production", null);
        AiNewsEventEntity linked = event("in_production", RUN_ID);
        when(eventService.findEvent(WORKSPACE_ID, EVENT_ID)).thenReturn(input, linked);
        when(teamMapper.selectOne(any())).thenReturn(team());
        when(teamService.listMembers(TEAM_ID)).thenReturn(members());
        stubAgents();

        TeamRunEntity run = new TeamRunEntity();
        run.setId(RUN_ID);
        run.setTeamId(TEAM_ID);
        run.setWorkspaceId(WORKSPACE_ID);
        when(runService.startRun(any())).thenReturn(run);
        when(taskService.listTasksByRun(RUN_ID)).thenReturn(List.of());
        AtomicLong taskId = new AtomicLong(1000L);
        when(taskService.createTask(any())).thenAnswer(invocation -> {
            TeamTaskCreateCommand command = invocation.getArgument(0);
            TeamTaskEntity task = new TeamTaskEntity();
            task.setId(taskId.getAndIncrement());
            task.setSubject(command.getSubject());
            return task;
        });

        AiNewsEventEntity result = service.start(WORKSPACE_ID, EVENT_ID);

        assertEquals(RUN_ID, result.getTeamRunId());
        ArgumentCaptor<TeamRunCreateCommand> runCommand =
                ArgumentCaptor.forClass(TeamRunCreateCommand.class);
        verify(runService).startRun(runCommand.capture());
        assertEquals(-EVENT_ID, runCommand.getValue().getOriginMessageId());
        assertEquals("ai-news-event-" + EVENT_ID, runCommand.getValue().getLeadConversationId());
        JsonNode runMetadata = new ObjectMapper().readTree(runCommand.getValue().getMetadata());
        assertEquals(String.valueOf(EVENT_ID), runMetadata.get("eventId").asText());
        assertTrue(runMetadata.get("requiresHumanApproval").asBoolean());

        ArgumentCaptor<TeamTaskCreateCommand> tasks =
                ArgumentCaptor.forClass(TeamTaskCreateCommand.class);
        verify(taskService, org.mockito.Mockito.times(5)).createTask(tasks.capture());
        List<TeamTaskCreateCommand> commands = tasks.getAllValues();
        assertEquals(List.of("整理事件证据包", "事实核验与冲突处理", "公众号文章打包交付",
                "小红书卡片打包交付", "合规扫描与人工审批"),
                commands.stream().map(TeamTaskCreateCommand::getSubject).toList());
        assertTrue(commands.get(0).getBlockedBy() == null || commands.get(0).getBlockedBy().isEmpty());
        assertEquals(List.of(1000L), commands.get(1).getBlockedBy());
        assertEquals(List.of(1001L), commands.get(2).getBlockedBy());
        assertEquals(List.of(1001L), commands.get(3).getBlockedBy());
        assertEquals(List.of(1002L, 1003L), commands.get(4).getBlockedBy());
        assertFalse(commands.get(2).isRequireApproval());
        assertFalse(commands.get(3).isRequireApproval());
        assertTrue(commands.get(4).isRequireApproval());
        assertTrue(commands.get(2).getMetadata().contains("deliverableRequired"));
        assertTrue(commands.get(3).getMetadata().contains("deliverableRequired"));

        verify(runService).sealRunWithResult(RUN_ID, WORKSPACE_ID);
        verify(eventService).linkRun(WORKSPACE_ID, EVENT_ID, RUN_ID);
        verify(eventPublisher).publishEvent(new TeamRunDispatchCommittedIntent(TEAM_ID));
    }

    private AgentTeamEntity team() {
        AgentTeamEntity team = new AgentTeamEntity();
        team.setId(TEAM_ID);
        team.setWorkspaceId(WORKSPACE_ID);
        team.setName("AI 动态内容生产组");
        team.setLeadAgentId(LEAD_ID);
        return team;
    }

    private List<AgentTeamMemberEntity> members() {
        return List.of(member(51L), member(52L), member(53L), member(54L), member(55L));
    }

    private AgentTeamMemberEntity member(long agentId) {
        AgentTeamMemberEntity member = new AgentTeamMemberEntity();
        member.setTeamId(TEAM_ID);
        member.setAgentId(agentId);
        member.setRole(TeamRole.MEMBER);
        return member;
    }

    private void stubAgents() {
        String[] roles = {"discover", "verify", "edit", "visual", "delivery"};
        for (int i = 0; i < roles.length; i++) {
            AgentEntity agent = new AgentEntity();
            agent.setId(51L + i);
            agent.setName(roles[i]);
            agent.setTags("ai-news,content-ops," + roles[i]);
            when(agentMapper.selectById(51L + i)).thenReturn(agent);
        }
    }

    private AiNewsEventEntity event(String status, Long runId) {
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(EVENT_ID);
        event.setWorkspaceId(WORKSPACE_ID);
        event.setEventKey("deepseek-event");
        event.setTitle("DeepSeek 发布模型更新");
        event.setStatus(status);
        event.setTeamRunId(runId);
        return event;
    }
}

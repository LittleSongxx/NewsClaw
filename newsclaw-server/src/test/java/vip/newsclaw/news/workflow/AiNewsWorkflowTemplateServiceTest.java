package vip.newsclaw.news.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.channel.model.ChannelEntity;
import vip.newsclaw.channel.repository.ChannelMapper;
import vip.newsclaw.trigger.service.TriggerService;
import vip.newsclaw.workflow.model.WorkflowEntity;
import vip.newsclaw.workflow.service.WorkflowService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiNewsWorkflowTemplateServiceTest {

    @Test
    void previewUsesParallelEditorialAndVisualFanOutAndHonestReadiness() throws Exception {
        AgentMapper agents = mock(AgentMapper.class);
        ChannelMapper channels = mock(ChannelMapper.class);
        when(agents.selectList(any())).thenReturn(roleAgents());
        ChannelEntity channel = new ChannelEntity();
        channel.setChannelType("web");
        channel.setEnabled(true);
        when(channels.selectList(any())).thenReturn(List.of(channel));
        AiNewsWorkflowTemplateService service = new AiNewsWorkflowTemplateService(
                agents, channels, new ObjectMapper(), mock(WorkflowService.class), mock(TriggerService.class));

        AiNewsWorkflowTemplate preview = service.preview(7L);
        JsonNode graph = new ObjectMapper().readTree(preview.draftJson());
        JsonNode steps = graph.get("steps");

        assertEquals("fan_out", steps.get(2).get("mode").get("type").asText());
        assertEquals("fan_out", steps.get(3).get("mode").get("type").asText());
        assertEquals("collect", steps.get(4).get("mode").get("type").asText());
        assertTrue(steps.get(0).get("promptTemplate").asText().contains("ai_news_event"));
        assertTrue(steps.get(1).get("promptTemplate").asText()
                .contains("ai_news_event(action=mark_verified"));
        assertFalse(steps.get(1).get("promptTemplate").asText()
                .contains("ai_news_event(action=verify)"));
        assertTrue(steps.get(5).get("mode").get("type").asText().equals("await_approval"));
        assertFalse(preview.readyForPublish());
        assertTrue(preview.missingFields().stream()
                .anyMatch(value -> value.contains("delivery target")));
    }

    @Test
    void previewReportsMissingRolesAndChannel() {
        AgentMapper agents = mock(AgentMapper.class);
        ChannelMapper channels = mock(ChannelMapper.class);
        when(agents.selectList(any())).thenReturn(List.of());
        when(channels.selectList(any())).thenReturn(List.of());
        AiNewsWorkflowTemplateService service = new AiNewsWorkflowTemplateService(
                agents, channels, new ObjectMapper(), mock(WorkflowService.class), mock(TriggerService.class));

        AiNewsWorkflowTemplate preview = service.preview(7L);

        assertFalse(preview.readyForPublish());
        assertTrue(preview.missingFields().contains("agent role: discover"));
        assertTrue(preview.missingFields().contains("enabled delivery channel"));
    }

    @Test
    void installKeepsTriggersDisabledWhileTemplateHasUnresolvedDeliveryTarget() {
        AgentMapper agents = mock(AgentMapper.class);
        ChannelMapper channels = mock(ChannelMapper.class);
        when(agents.selectList(any())).thenReturn(roleAgents());
        ChannelEntity channel = new ChannelEntity();
        channel.setChannelType("web");
        channel.setEnabled(true);
        when(channels.selectList(any())).thenReturn(List.of(channel));

        WorkflowService workflows = mock(WorkflowService.class);
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(99L);
        when(workflows.listByWorkspace(7L)).thenReturn(List.of());
        when(workflows.create(any())).thenReturn(workflow);
        when(workflows.saveDraft(eq(99L), eq(7L), any(), eq(5L))).thenReturn(workflow);

        TriggerService triggers = mock(TriggerService.class);
        when(triggers.listByWorkspace(7L)).thenReturn(List.of());
        when(triggers.create(any(), eq(7L))).thenAnswer(invocation -> invocation.getArgument(0));

        AiNewsWorkflowTemplateService service = new AiNewsWorkflowTemplateService(
                agents, channels, new ObjectMapper(), workflows, triggers);

        AiNewsWorkflowTemplateService.InstallationResult result = service.install(7L, 5L, true);

        assertFalse(result.missingFields().isEmpty());
        assertTrue(result.triggers().stream().noneMatch(row -> Boolean.TRUE.equals(row.getEnabled())));
    }

    private static List<AgentEntity> roleAgents() {
        List<AgentEntity> result = new ArrayList<>();
        for (String role : List.of("discover", "verify", "edit", "visual", "delivery")) {
            AgentEntity agent = new AgentEntity();
            agent.setId((long) result.size() + 1);
            agent.setName(role);
            agent.setTags("ai-news,content-ops," + role);
            agent.setEnabled(true);
            result.add(agent);
        }
        return result;
    }
}

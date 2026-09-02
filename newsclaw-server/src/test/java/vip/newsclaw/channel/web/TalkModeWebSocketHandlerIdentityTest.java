package vip.newsclaw.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import vip.newsclaw.agent.AgentService;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.memory.event.ConversationCompletionPublisher;
import vip.newsclaw.stt.SttService;
import vip.newsclaw.tts.TtsService;
import vip.newsclaw.workspace.conversation.ConversationService;
import vip.newsclaw.workspace.core.service.WorkspaceService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TalkModeWebSocketHandlerIdentityTest {

    @Test
    void initIgnoresClientUsernameAndPinsHandshakeIdentity() throws Exception {
        AgentService agents = mock(AgentService.class);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        TalkModeWebSocketHandler handler = handler(agents, workspaces);
        AgentEntity agent = agent(9L, 2L);
        when(agents.getAgent(9L)).thenReturn(agent);
        when(workspaces.hasPermission(2L, 7L, "viewer")).thenReturn(true);
        WebSocketSession session = session("alice", 7L, "user");

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"init\",\"agentId\":9,\"conversationId\":\"c1\",\"username\":\"mallory\"}"));

        Object talkSession = sessions(handler).get("ws-1");
        Method username = talkSession.getClass().getDeclaredMethod("username");
        username.setAccessible(true);
        assertThat(username.invoke(talkSession)).isEqualTo("alice");
    }

    @Test
    void initRejectsAgentOutsideAuthenticatedUsersWorkspaces() throws Exception {
        AgentService agents = mock(AgentService.class);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        TalkModeWebSocketHandler handler = handler(agents, workspaces);
        when(agents.getAgent(9L)).thenReturn(agent(9L, 2L));
        when(workspaces.hasPermission(2L, 7L, "viewer")).thenReturn(false);

        handler.handleTextMessage(session("alice", 7L, "user"), new TextMessage(
                "{\"type\":\"init\",\"agentId\":9,\"conversationId\":\"c1\"}"));

        assertThat(sessions(handler)).isEmpty();
    }

    private static TalkModeWebSocketHandler handler(AgentService agents, WorkspaceService workspaces) {
        return new TalkModeWebSocketHandler(
                mock(SttService.class), mock(TtsService.class), agents,
                mock(ConversationService.class), mock(ConversationCompletionPublisher.class),
                new ObjectMapper(), workspaces);
    }

    private static WebSocketSession session(String username, Long userId, String role) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(TalkModeHandshakeInterceptor.USERNAME_ATTR, username);
        attrs.put(TalkModeHandshakeInterceptor.USER_ID_ATTR, userId);
        attrs.put(TalkModeHandshakeInterceptor.ROLE_ATTR, role);
        when(session.getId()).thenReturn("ws-1");
        when(session.getAttributes()).thenReturn(attrs);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sessions(TalkModeWebSocketHandler handler) throws Exception {
        Field field = TalkModeWebSocketHandler.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(handler);
    }

    private static AgentEntity agent(Long id, Long workspaceId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setWorkspaceId(workspaceId);
        agent.setEnabled(true);
        return agent;
    }
}

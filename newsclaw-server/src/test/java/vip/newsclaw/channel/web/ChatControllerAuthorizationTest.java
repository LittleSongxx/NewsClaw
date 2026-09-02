package vip.newsclaw.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import vip.newsclaw.agent.AgentService;
import vip.newsclaw.approval.ApprovalWorkflowService;
import vip.newsclaw.common.result.R;
import vip.newsclaw.memory.event.ConversationCompletionPublisher;
import vip.newsclaw.memory.identity.MemoryOwnerResolver;
import vip.newsclaw.tool.document.preview.OfficePreviewService;
import vip.newsclaw.workspace.conversation.ConversationService;
import vip.newsclaw.workspace.core.service.ChatUploadLocationResolver;
import vip.newsclaw.workspace.core.service.WorkspaceService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerAuthorizationTest {

    private AgentService agentService;
    private ConversationService conversationService;
    private WorkspaceService workspaceService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        conversationService = mock(ConversationService.class);
        workspaceService = mock(WorkspaceService.class);
        controller = new ChatController(
                agentService, conversationService, mock(ApprovalWorkflowService.class),
                mock(ChatStreamTracker.class), new ObjectMapper(),
                mock(ConversationCompletionPublisher.class), mock(MemoryOwnerResolver.class),
                mock(ChatUploadLocationResolver.class), mock(OfficePreviewService.class),
                workspaceService);
    }

    @Test
    void syncChatRejectsUserOutsideRequestedWorkspace() {
        ChatController.ChatRequest request = new ChatController.ChatRequest();
        request.setConversationId("c1");
        request.setMessage("hello");
        UsernamePasswordAuthenticationToken auth = user("alice", 7L);
        when(workspaceService.hasPermission(2L, 7L, "viewer")).thenReturn(false);

        R<String> result = controller.chat(99L, request, 2L, auth);

        assertThat(result.getCode()).isEqualTo(403);
        verify(agentService, never()).getAgent(99L);
    }

    @Test
    void syncChatRejectsClientAssertedEndUserId() {
        ChatController.ChatRequest request = new ChatController.ChatRequest();
        request.setConversationId("c1");
        request.setMessage("hello");
        request.setEndUserId("someone-else");
        UsernamePasswordAuthenticationToken auth = user("alice", 7L);
        when(workspaceService.hasPermission(1L, 7L, "viewer")).thenReturn(true);

        R<String> result = controller.chat(99L, request, 1L, auth);

        assertThat(result.getCode()).isEqualTo(403);
        verify(agentService, never()).getAgent(99L);
    }

    @Test
    void anonymousStopIsRejectedWithoutTouchingRunState() {
        R<java.util.Map<String, Object>> result = controller.stopStream("victim", null);

        assertThat(result.getCode()).isEqualTo(401);
        verify(conversationService, never()).isConversationOwner("victim", "anonymous");
    }

    @Test
    void anonymousReconnectIsRejectedBeforeConversationLookup() {
        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setConversationId("victim");
        request.setReconnect(true);

        controller.chatStream(request, 1L, null);

        verify(conversationService, never()).findByConversationId("victim");
    }

    private static UsernamePasswordAuthenticationToken user(String username, long userId) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, null, List.of());
        auth.setDetails(userId);
        return auth;
    }
}

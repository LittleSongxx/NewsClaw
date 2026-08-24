package vip.newsclaw.news.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.channel.ChannelManager;
import vip.newsclaw.channel.ChannelSessionStore;
import vip.newsclaw.channel.feishu.FeishuChannelAdapter;
import vip.newsclaw.channel.feishu.cards.ai_news.AiNewsReviewCardPayload;
import vip.newsclaw.channel.model.ChannelSessionEntity;
import vip.newsclaw.news.model.AiNewsEventDetail;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.service.AiNewsEventService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsReviewCardToolTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long CHANNEL_ID = 88L;
    private static final String CONVERSATION_ID = "feishu:oc_chat";
    private static final String TARGET_ID = "oc_chat";
    private static final String REQUESTER_ID = "ou_requester";

    private AiNewsEventService eventService;
    private ChannelSessionStore sessionStore;
    private ChannelManager channelManager;
    private FeishuChannelAdapter feishu;
    private ObjectMapper objectMapper;
    private AiNewsReviewCardTool tool;

    @BeforeEach
    void setUp() {
        eventService = mock(AiNewsEventService.class);
        sessionStore = mock(ChannelSessionStore.class);
        channelManager = mock(ChannelManager.class);
        feishu = mock(FeishuChannelAdapter.class);
        objectMapper = new ObjectMapper();
        tool = new AiNewsReviewCardTool(eventService, sessionStore, channelManager, objectMapper);
    }

    @Test
    void sendsStructuredEventIdsToCurrentFeishuSessionWithinWorkspace() throws Exception {
        when(sessionStore.getSession(CONVERSATION_ID)).thenReturn(session());
        when(channelManager.getAdapter(CHANNEL_ID)).thenReturn(Optional.of(feishu));
        when(eventService.get(WORKSPACE_ID, 101L)).thenReturn(detail(101L, "candidate"));
        when(eventService.get(WORKSPACE_ID, 102L)).thenReturn(detail(102L, "verified"));
        when(feishu.sendAiNewsReviewCard(eq(TARGET_ID), any())).thenReturn(true);

        String result = tool.ai_news_review_card("[\"101\", 102, 101]", origin().toToolContext());

        JsonNode json = objectMapper.readTree(result);
        assertEquals(List.of("101", "102"), objectMapper.convertValue(json.get("sent"), List.class));
        assertEquals(CONVERSATION_ID, json.get("targetConversation").asText());
        assertTrue(json.get("failed").isEmpty());
        verify(eventService).get(WORKSPACE_ID, 101L);
        verify(eventService).get(WORKSPACE_ID, 102L);

        ArgumentCaptor<AiNewsReviewCardPayload> payloads =
                ArgumentCaptor.forClass(AiNewsReviewCardPayload.class);
        verify(feishu, org.mockito.Mockito.times(2))
                .sendAiNewsReviewCard(eq(TARGET_ID), payloads.capture());
        assertEquals(List.of(101L, 102L), payloads.getAllValues().stream()
                .map(AiNewsReviewCardPayload::eventId).toList());
        assertTrue(payloads.getAllValues().stream()
                .allMatch(payload -> WORKSPACE_ID == payload.workspaceId()
                        && REQUESTER_ID.equals(payload.requesterOpenId())));
        assertEquals("official", payloads.getAllValues().getFirst().primaryEvidenceTier());
    }

    @Test
    void workspaceScopedLookupFailureDoesNotSendACard() throws Exception {
        when(sessionStore.getSession(CONVERSATION_ID)).thenReturn(session());
        when(channelManager.getAdapter(CHANNEL_ID)).thenReturn(Optional.of(feishu));
        when(eventService.get(WORKSPACE_ID, 999L))
                .thenThrow(new IllegalArgumentException("事件不存在或不属于当前 workspace"));

        JsonNode result = objectMapper.readTree(
                tool.ai_news_review_card("999", origin().toToolContext()));

        assertTrue(result.get("sent").isEmpty());
        assertEquals("999", result.get("failed").get(0).get("eventId").asText());
        assertTrue(result.get("failed").get(0).get("reason").asText().contains("workspace"));
        verify(feishu, never()).sendAiNewsReviewCard(any(), any());
    }

    @Test
    void nonFeishuOriginIsRejectedBeforeResolvingSession() throws Exception {
        ChatOrigin webOrigin = ChatOrigin.web(CONVERSATION_ID, REQUESTER_ID, WORKSPACE_ID, null);

        JsonNode result = objectMapper.readTree(
                tool.ai_news_review_card("101", webOrigin.toToolContext()));

        assertTrue(result.get("error").asText().contains("飞书会话"));
        verify(sessionStore, never()).getSession(any());
        verify(eventService, never()).get(any(), any());
    }

    @Test
    void naturalLanguageEventReferenceIsRejectedInsteadOfGuessingAnId() throws Exception {
        when(sessionStore.getSession(CONVERSATION_ID)).thenReturn(session());
        when(channelManager.getAdapter(CHANNEL_ID)).thenReturn(Optional.of(feishu));

        JsonNode result = objectMapper.readTree(
                tool.ai_news_review_card("event-101", origin().toToolContext()));

        assertTrue(result.hasNonNull("error"));
        verify(eventService, never()).get(any(), any());
        verify(feishu, never()).sendAiNewsReviewCard(any(), any());
    }

    private ChatOrigin origin() {
        return new ChatOrigin(null, CONVERSATION_ID, REQUESTER_ID, WORKSPACE_ID, null,
                CHANNEL_ID, null, false, "Requester", "feishu", TARGET_ID,
                null, null, null);
    }

    private ChannelSessionEntity session() {
        ChannelSessionEntity session = new ChannelSessionEntity();
        session.setConversationId(CONVERSATION_ID);
        session.setChannelType("feishu");
        session.setChannelId(CHANNEL_ID);
        session.setTargetId(TARGET_ID);
        return session;
    }

    private AiNewsEventDetail detail(long eventId, String status) {
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(eventId);
        event.setWorkspaceId(WORKSPACE_ID);
        event.setTitle("AI event " + eventId);
        event.setSummary("Verified summary");
        event.setCategory("model");
        event.setStatus(status);
        event.setConfidence(0.9);

        AiNewsEvidenceEntity evidence = new AiNewsEvidenceEntity();
        evidence.setEventId(eventId);
        evidence.setWorkspaceId(WORKSPACE_ID);
        evidence.setSourceTier("official");
        evidence.setVerified(true);
        return new AiNewsEventDetail(event, List.of(evidence), List.of());
    }
}

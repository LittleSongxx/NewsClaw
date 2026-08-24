package vip.newsclaw.channel.feishu.cards.ai_news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.newsclaw.audit.service.AuditEventService;
import vip.newsclaw.channel.feishu.FeishuChannelAdapter;
import vip.newsclaw.channel.model.ChannelEntity;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.service.AiNewsProductionService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsReviewCardHandlerTest {

    private AiNewsEventService eventService;
    private AiNewsProductionService productionService;
    private FeishuChannelAdapter adapter;
    private AiNewsReviewButtonValue codec;
    private AiNewsReviewCardHandler handler;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        eventService = mock(AiNewsEventService.class);
        productionService = mock(AiNewsProductionService.class);
        adapter = mock(FeishuChannelAdapter.class);
        ChannelEntity channel = new ChannelEntity();
        channel.setWorkspaceId(7L);
        when(adapter.getChannelEntity()).thenReturn(channel);
        codec = new AiNewsReviewButtonValue(objectMapper);
        handler = new AiNewsReviewCardHandler(codec, eventService, productionService,
                mock(AuditEventService.class), objectMapper);
    }

    @Test
    void onlyOriginalRequesterCanActAndCardStaysRetryable() {
        var response = handler.handle(adapter, data(AiNewsReviewButtonValue.Action.VERIFY, "ou_other"));

        assertEquals("warning", response.getToast().getType());
        assertNull(response.getCard());
        verify(eventService, never()).verify(any(), any(), any(), any());
    }

    @Test
    void verifiedActionRunsWorkspaceScopedStateMachine() {
        AiNewsEventEntity event = event("verified");
        when(eventService.findEvent(7L, 101L)).thenReturn(event);
        when(eventService.verify(7L, 101L, null, null)).thenReturn(event);

        var response = handler.handle(adapter, data(AiNewsReviewButtonValue.Action.VERIFY, "ou_requester"));

        verify(eventService).verify(7L, 101L, null, null);
        assertNotNull(response.getCard());
        assertEquals("info", response.getToast().getType());
    }

    @Test
    void startRunRequiresBackendTransitionBeforeProduction() {
        AiNewsEventEntity inProduction = event("in_production");
        inProduction.setTeamRunId(9001L);
        when(eventService.findEvent(7L, 101L)).thenReturn(event("verified"));
        when(eventService.beginProduction(7L, 101L)).thenReturn(inProduction);
        when(productionService.start(7L, 101L)).thenReturn(inProduction);

        var response = handler.handle(adapter, data(AiNewsReviewButtonValue.Action.START_RUN, "ou_requester"));

        verify(eventService).beginProduction(7L, 101L);
        verify(productionService).start(7L, 101L);
        assertNotNull(response.getCard());
    }

    private P2CardActionTriggerData data(AiNewsReviewButtonValue.Action action, String clicker) {
        CallBackAction callback = new CallBackAction();
        callback.setValue(codec.encode(action, 101L, 7L, "ou_requester"));
        CallBackOperator operator = new CallBackOperator();
        operator.setOpenId(clicker);
        P2CardActionTriggerData data = new P2CardActionTriggerData();
        data.setAction(callback);
        data.setOperator(operator);
        return data;
    }

    private static AiNewsEventEntity event(String status) {
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(101L);
        event.setWorkspaceId(7L);
        event.setTitle("OpenAI 发布模型");
        event.setStatus(status);
        return event;
    }
}

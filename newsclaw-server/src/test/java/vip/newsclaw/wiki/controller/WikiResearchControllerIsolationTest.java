package vip.newsclaw.wiki.controller;

import org.junit.jupiter.api.Test;
import vip.newsclaw.channel.web.ChatStreamTracker;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;
import vip.newsclaw.wiki.service.WikiKnowledgeBaseService;
import vip.newsclaw.wiki.service.WikiResearchService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WikiResearchControllerIsolationTest {

    @Test
    void startRejectsForeignKnowledgeBase() {
        WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(10L);
        kb.setWorkspaceId(2L);
        when(kbService.getById(10L)).thenReturn(kb);
        WikiResearchController controller = new WikiResearchController(
                mock(WikiResearchService.class), kbService, mock(ChatStreamTracker.class));

        assertThatThrownBy(() -> controller.startResearch(
                Map.of("kbId", 10L, "topic", "private"), 1L))
                .isInstanceOf(NewsClawException.class);
    }

    @Test
    void streamRejectsAResearchSessionFromAnotherWorkspace() {
        WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(10L);
        kb.setWorkspaceId(1L);
        when(kbService.getById(10L)).thenReturn(kb);
        ChatStreamTracker tracker = mock(ChatStreamTracker.class);
        WikiResearchController controller = new WikiResearchController(
                mock(WikiResearchService.class), kbService, tracker);
        String sessionId = String.valueOf(controller.startResearch(
                Map.of("kbId", 10L, "topic", "topic"), 1L).getData().get("sessionId"));

        controller.stream(sessionId, 2L);

        verify(tracker, never()).attach(eq(sessionId), any());
    }
}

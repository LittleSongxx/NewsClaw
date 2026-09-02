package vip.newsclaw.news.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.service.AiNewsSourceCaptureService;
import vip.newsclaw.news.service.OfficialSourceEvidenceCaptureService;
import vip.newsclaw.workspace.conversation.ConversationService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tool argument aliases are part of the LLM-facing contract and must stay compatible. */
class AiNewsEventToolAliasTest {

    @Test
    void editorialMutationWithoutOriginIsRejectedInsteadOfUsingDefaultTenant() {
        AiNewsEventService events = mock(AiNewsEventService.class);
        AiNewsEventTool tool = new AiNewsEventTool(events, mock(OfficialSourceEvidenceCaptureService.class),
                mock(AiNewsSourceCaptureService.class), mock(ConversationService.class), new ObjectMapper());

        String out = linkWiki(tool, null);

        assertTrue(out.startsWith("Error: AI news event mutation requires an explicit workspace context"), out);
        verifyNoInteractions(events);
    }

    @Test
    void pageIdAliasLinksWikiPage() {
        AiNewsEventService events = mock(AiNewsEventService.class);
        when(events.linkWiki(1L, 101L, 202L)).thenReturn(new AiNewsEventEntity());
        AiNewsEventTool tool = new AiNewsEventTool(events, mock(OfficialSourceEvidenceCaptureService.class),
                mock(AiNewsSourceCaptureService.class),
                mock(ConversationService.class), new ObjectMapper());

        String out = linkWiki(tool, ChatOrigin.web("conv-1", "user-1", 1L, "/workspace/1", null, 1L).toToolContext());

        assertTrue(out.contains("{}") || out.contains("wikiPageId"), out);
        verify(events).linkWiki(1L, 101L, 202L);
    }

    @Test
    void chatOriginWorkspaceTakesPrecedenceOverConversationLookup() {
        AiNewsEventService events = mock(AiNewsEventService.class);
        ConversationService conversations = mock(ConversationService.class);
        when(events.linkWiki(77L, 101L, 202L)).thenReturn(new AiNewsEventEntity());
        AiNewsEventTool tool = new AiNewsEventTool(events, mock(OfficialSourceEvidenceCaptureService.class),
                mock(AiNewsSourceCaptureService.class),
                conversations, new ObjectMapper());

        ToolContext ctx = ChatOrigin.web("conv-1", "user-1", 77L, "/workspace/77",
                null, 77L).toToolContext();
        linkWiki(tool, ctx);

        verify(events).linkWiki(77L, 101L, 202L);
        verify(conversations, never()).findByConversationId(anyString());
    }

    private static String linkWiki(AiNewsEventTool tool, ToolContext ctx) {
        return tool.ai_news_event(
                "link_wiki", "101", null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, "202", null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                ctx);
    }
}

package vip.mate.news.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.news.model.AiNewsEventEntity;
import vip.mate.news.service.AiNewsEventService;
import vip.mate.news.service.OfficialSourceEvidenceCaptureService;
import vip.mate.workspace.conversation.ConversationService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tool argument aliases are part of the LLM-facing contract and must stay compatible. */
class AiNewsEventToolAliasTest {

    @Test
    void pageIdAliasLinksWikiPage() {
        AiNewsEventService events = mock(AiNewsEventService.class);
        when(events.linkWiki(1L, 101L, 202L)).thenReturn(new AiNewsEventEntity());
        AiNewsEventTool tool = new AiNewsEventTool(events, mock(OfficialSourceEvidenceCaptureService.class),
                mock(ConversationService.class), new ObjectMapper());

        String out = tool.ai_news_event("link_wiki", "101", null, null, null, null, null,
                null, null, null, null, null, null, "202", null, null, null, null, null);

        assertTrue(out.contains("{}") || out.contains("wikiPageId"), out);
        verify(events).linkWiki(1L, 101L, 202L);
    }
}

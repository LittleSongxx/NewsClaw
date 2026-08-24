package vip.mate.tool.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.content.service.ContentItemService;
import vip.mate.news.service.AiNewsEventService;
import vip.mate.tool.document.GeneratedFileCache;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The gzh packager must preserve the event relationship even when the model omits eventId. */
class GzhPackageEventLinkTest {

    private ContentItemService contentItems;
    private AiNewsEventService events;
    private GzhPackageTool tool;
    private ToolContext context;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        GeneratedFileCache cache = new GeneratedFileCache(tempDir);
        contentItems = mock(ContentItemService.class);
        events = mock(AiNewsEventService.class);
        tool = new GzhPackageTool(cache, contentItems);
        ReflectionTestUtils.setField(tool, "aiNewsEventService", events);
        context = ChatOrigin.web("ai-news-event-123", "tester", 7L, null).toToolContext();
        when(contentItems.record(anyLong(), eq("gzh"), anyString(), anyString(), eq("packaged"), any(), isNull()))
                .thenReturn(9001L);
    }

    @Test
    @DisplayName("conversation origin supplies event id and links the packaged item")
    void linksFromConversationOrigin() {
        String out = tool.gzh_package("AI 动态", "## 事实\n\n已核验。", null,
                "AI 动态主编", "模型发布", null, context);

        assertTrue(out.contains("已回链 AI 动态事件"), out);
        verify(events).linkContent(7L, 123L, 9001L, "gzh");
    }
}

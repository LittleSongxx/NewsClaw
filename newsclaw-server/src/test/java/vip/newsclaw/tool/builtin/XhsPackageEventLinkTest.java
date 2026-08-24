package vip.newsclaw.tool.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.content.service.ContentItemService;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.tool.document.GeneratedFileCache;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The xhs packager must preserve the event relationship when invoked by Team Run. */
class XhsPackageEventLinkTest {

    private GeneratedFileCache cache;
    private ContentItemService contentItems;
    private AiNewsEventService events;
    private XhsPackageTool tool;
    private ToolContext context;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        cache = new GeneratedFileCache(tempDir);
        contentItems = mock(ContentItemService.class);
        events = mock(AiNewsEventService.class);
        tool = new XhsPackageTool(cache, contentItems);
        ReflectionTestUtils.setField(tool, "aiNewsEventService", events);
        context = ChatOrigin.web("ai-news-event-456", "tester", 8L, null).toToolContext();
        when(contentItems.record(anyLong(), eq("xhs"), anyString(), anyString(), eq("packaged"), any(), isNull()))
                .thenReturn(9002L);
    }

    @Test
    @DisplayName("conversation origin supplies event id and links the packaged item")
    void linksFromConversationOrigin() {
        String i1 = "/api/v1/files/generated/" + cache.put(new byte[]{1}, "01.png", "image/png", context);
        String i2 = "/api/v1/files/generated/" + cache.put(new byte[]{2}, "02.png", "image/png", context);
        String i3 = "/api/v1/files/generated/" + cache.put(new byte[]{3}, "03.png", "image/png", context);

        String out = tool.xhs_package("AI 动态卡片", "已核验事实", "AI,模型",
                String.join(",", i1, i2, i3), "模型发布", null, context);

        assertTrue(out.contains("已回链 AI 动态事件"), out);
        verify(events).linkContent(8L, 456L, 9002L, "xhs");
    }
}

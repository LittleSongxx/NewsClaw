package vip.mate.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.content.service.ContentItemService;
import vip.mate.news.service.AiNewsEvidenceBoundaryService;
import vip.mate.tool.document.GeneratedFileCache;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Both final content packagers must fail before writing calendar rows on a provenance violation. */
class AiNewsPackageEvidenceBoundaryTest {

    @Test
    @DisplayName("公众号打包在证据边界失败时不写入内容日历")
    void gzhRejectsBeforePackaging(@TempDir Path tempDir) {
        ContentItemService contentItems = mock(ContentItemService.class);
        GzhPackageTool tool = new GzhPackageTool(new GeneratedFileCache(tempDir), contentItems);
        installRejectingBoundary(tool);

        String out = tool.gzh_package("AI 动态", "来源：官方 X 账号", null,
                "主编", "模型", null,
                ChatOrigin.web("ai-news-event-99", "tester", 7L, null).toToolContext());

        assertTrue(out.contains("证据边界拦截"), out);
        verifyNoInteractions(contentItems);
    }

    @Test
    @DisplayName("小红书打包在证据边界失败时不解析图片或写入内容日历")
    void xhsRejectsBeforePackaging(@TempDir Path tempDir) {
        ContentItemService contentItems = mock(ContentItemService.class);
        XhsPackageTool tool = new XhsPackageTool(new GeneratedFileCache(tempDir), contentItems);
        installRejectingBoundary(tool);

        String out = tool.xhs_package("AI 动态", "基于官方 X 账号", "AI", "", "模型", null,
                ChatOrigin.web("ai-news-event-99", "tester", 7L, null).toToolContext());

        assertTrue(out.contains("证据边界拦截"), out);
        verifyNoInteractions(contentItems);
    }

    private static void installRejectingBoundary(Object tool) {
        AiNewsEvidenceBoundaryService boundary = mock(AiNewsEvidenceBoundaryService.class);
        when(boundary.validate(eq(7L), eq(99L), anyString())).thenReturn(
                new AiNewsEvidenceBoundaryService.ValidationResult(false,
                        List.of("提到官方 X/Twitter 账号，但事件证据未归档对应来源"), List.of()));
        ReflectionTestUtils.setField(tool, "aiNewsEvidenceBoundaryService", boundary);
    }
}

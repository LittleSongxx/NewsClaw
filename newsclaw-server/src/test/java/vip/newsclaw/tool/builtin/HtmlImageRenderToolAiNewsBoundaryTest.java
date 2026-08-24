package vip.newsclaw.tool.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.news.service.AiNewsEvidenceBoundaryService;
import vip.newsclaw.tool.document.GeneratedFileCache;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The card renderer must stop before opening Playwright when source grounding fails. */
class HtmlImageRenderToolAiNewsBoundaryTest {

    @Test
    @DisplayName("AI-news card with an unarchived source is rejected before browser rendering")
    void rejectsBeforeRender(@TempDir Path tempDir) {
        HtmlImageRenderTool tool = new HtmlImageRenderTool(new GeneratedFileCache(tempDir));
        AiNewsEvidenceBoundaryService boundary = mock(AiNewsEvidenceBoundaryService.class);
        when(boundary.validate(7L, 99L, "<p>官方 X 账号</p>")).thenReturn(
                new AiNewsEvidenceBoundaryService.ValidationResult(false,
                        List.of("提到官方 X/Twitter 账号，但事件证据未归档对应来源"), List.of()));
        ReflectionTestUtils.setField(tool, "aiNewsEvidenceBoundaryService", boundary);

        String out = tool.render_html_image(null, "<p>官方 X 账号</p>", "card", 1080, 1440, false,
                ChatOrigin.web("ai-news-event-99", "tester", 7L, null).toToolContext());

        assertTrue(out.contains("evidence boundary rejected"), out);
    }

    @Test
    @DisplayName("layout diagnostics from Playwright are normalized without blank noise")
    void normalizesLayoutDiagnostics() {
        assertEquals(List.of("画布纵向溢出 120px", "ul 内容侵入页脚安全区"),
                HtmlImageRenderTool.layoutProblems(
                        List.of("画布纵向溢出 120px", "", "ul 内容侵入页脚安全区")));
        assertTrue(HtmlImageRenderTool.layoutProblems("not-a-list").isEmpty());
    }
}

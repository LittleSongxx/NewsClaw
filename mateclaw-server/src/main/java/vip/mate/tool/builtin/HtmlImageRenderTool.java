package vip.mate.tool.builtin;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.tool.browser.BrowserLauncher;
import vip.mate.tool.document.FilenameSanitizer;
import vip.mate.tool.document.GeneratedFileCache;
import vip.mate.tool.document.GeneratedFileLink;
import vip.mate.tool.guard.WorkspacePathGuard;
import vip.mate.news.service.AiNewsEvidenceBoundaryService;
import vip.mate.agent.context.ChatOrigin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Render arbitrary HTML to a PNG and return a one-time download URL.
 *
 * <p>Bridges the gap between HTML-producing skills (architecture diagrams,
 * infographics, dashboards) and IM channels whose native message types only
 * accept rasterised images. The PNG is stashed in {@link GeneratedFileCache}
 * with an {@code image/png} MIME so the per-channel sniff layer
 * ({@code WeComChannelAdapter}, {@code DingTalkChannelAdapter}, …) uploads it
 * as a native image attachment rather than a fallback file.
 */
@Slf4j
@Component
public class HtmlImageRenderTool {

    private static final String PNG_MIME = "image/png";
    private static final int DEFAULT_VIEWPORT_WIDTH = 1440;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 900;
    private static final int MAX_VIEWPORT_DIMENSION = 4096;
    private static final int SET_CONTENT_TIMEOUT_MS = 15_000;
    private static final String AI_NEWS_LAYOUT_CHECK = """
            () => {
              const problems = [];
              const root = document.documentElement;
              const body = document.body;
              const vw = root.clientWidth;
              const vh = root.clientHeight;
              const docWidth = Math.max(root.scrollWidth, body ? body.scrollWidth : 0);
              const docHeight = Math.max(root.scrollHeight, body ? body.scrollHeight : 0);
              if (docWidth > vw + 2) problems.push(`画布横向溢出 ${docWidth - vw}px`);
              if (docHeight > vh + 2) problems.push(`画布纵向溢出 ${docHeight - vh}px`);

              const footer = document.querySelector('footer, .footer');
              if (footer) {
                const footerRect = footer.getBoundingClientRect();
                const selectors = 'main, ul, ol, .content, .cta, .tags, .legend';
                for (const element of document.querySelectorAll(selectors)) {
                  if (footer.contains(element) || element.contains(footer)) continue;
                  const rect = element.getBoundingClientRect();
                  if (rect.height > 0 && rect.top < footerRect.top && rect.bottom > footerRect.top - 8) {
                    problems.push(`${element.tagName.toLowerCase()} 内容侵入页脚安全区`);
                    break;
                  }
                }
              }

              for (const element of document.querySelectorAll('strong, code, .model-name')) {
                const text = (element.textContent || '').trim();
                if (text.length < 12) continue;
                const rect = element.getBoundingClientRect();
                const style = getComputedStyle(element);
                const fontSize = parseFloat(style.fontSize) || 16;
                const lineHeight = parseFloat(style.lineHeight) || fontSize * 1.2;
                const lines = rect.height / lineHeight;
                if (rect.width < fontSize * 2.2 && lines >= 4) {
                  problems.push(`长英文标识被压入过窄列：${text.slice(0, 32)}`);
                  break;
                }
              }
              return problems.slice(0, 5);
            }
            """;

    private final GeneratedFileCache cache;

    /** Active only for Team Run conversations named {@code ai-news-event-{id}}. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiNewsEvidenceBoundaryService aiNewsEvidenceBoundaryService;

    private volatile Playwright sharedPlaywright;
    private final Object playwrightLock = new Object();

    public HtmlImageRenderTool(GeneratedFileCache cache) {
        this.cache = cache;
    }

    @vip.mate.tool.ConcurrencyUnsafe("Playwright's Java client is not thread-safe; serialize browser rendering")
    @Tool(description = """
        Render HTML to a PNG image and return a one-time download URL.

        Use this whenever the user wants an HTML artifact (architecture
        diagram, infographic, dashboard, mockup, ...) delivered as an
        *image* — especially when the chat is happening on an IM channel
        (WeCom / 企业微信, DingTalk, Feishu, Telegram, Discord) where users
        cannot click through a raw HTML link.

        The returned URL is `/api/v1/files/generated/<id>` with MIME
        `image/png`. Channel adapters detect this MIME and upload the
        bytes as a native image message, so the recipient sees an inline
        picture rather than a file attachment.

        Typical workflow when paired with an HTML-producing skill:
          1. write_file(filePath="diagram.html", content="<html>...")
          2. render_html_image(filePath="diagram.html", filename="diagram")
          3. return the markdown link to the user

        Or directly, without going through disk:
          1. render_html_image(html="<html>...", filename="diagram")

        Exactly one of `filePath` or `html` must be supplied. The link is
        valid for 10 minutes.
        """)
    public String render_html_image(
            @ToolParam(description = "Path to an HTML file on disk (workspace-relative or absolute). Mutually exclusive with `html`.", required = false)
            String filePath,
            @ToolParam(description = "Inline HTML source. Mutually exclusive with `filePath`.", required = false)
            String html,
            @ToolParam(description = "Output filename without extension, e.g. 'architecture'")
            String filename,
            @ToolParam(description = "Viewport width in px (default 1440, max 4096)", required = false)
            Integer width,
            @ToolParam(description = "Viewport height in px (default 900, max 4096). Ignored when fullPage=true except as initial layout hint.", required = false)
            Integer height,
            @ToolParam(description = "Capture full scrollable page (default true). Set false to only capture the viewport.", required = false)
            Boolean fullPage,
            @Nullable ToolContext ctx) {

        String source;
        try {
            source = resolveHtml(filePath, html);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.error("[HtmlImageRender] failed to load HTML: {}", e.getMessage(), e);
            return "Error: failed to load HTML — " + e.getMessage();
        }

        String evidenceBoundaryError = validateAiNewsEvidenceBoundary(source, ctx);
        if (evidenceBoundaryError != null) {
            return "Error: AI-news evidence boundary rejected this card: " + evidenceBoundaryError;
        }

        int vw = clampViewport(width, DEFAULT_VIEWPORT_WIDTH);
        int vh = clampViewport(height, DEFAULT_VIEWPORT_HEIGHT);
        boolean full = fullPage == null || fullPage;
        String displayName = FilenameSanitizer.sanitize(filename, "image", ".png") + ".png";

        byte[] pngBytes;
        try {
            pngBytes = renderToPng(source, vw, vh, full, isAiNewsContext(ctx));
        } catch (Exception e) {
            log.error("[HtmlImageRender] render failed for {}: {}", displayName, e.getMessage(), e);
            String hint = e.getMessage() != null && e.getMessage().contains("Executable doesn't exist")
                    ? " Hint: run `mvn exec:java -e -Dexec.mainClass=\"com.microsoft.playwright.CLI\" -Dexec.args=\"install chromium\"` to install the bundled browser."
                    : "";
            return "Render failed: " + e.getMessage() + hint;
        }

        log.info("[HtmlImageRender] rendered {} ({} bytes, viewport={}x{}, fullPage={})",
                displayName, pngBytes.length, vw, vh, full);
        return GeneratedFileLink.resultZh(pngBytes, displayName, PNG_MIME, cache, "图片", ctx);
    }

    private String resolveHtml(String filePath, String inlineHtml) throws Exception {
        boolean hasPath = filePath != null && !filePath.isBlank();
        boolean hasInline = inlineHtml != null && !inlineHtml.isBlank();
        if (hasPath == hasInline) {
            throw new IllegalArgumentException(
                    "Provide exactly one of `filePath` or `html` (not both, not neither).");
        }
        if (hasPath) {
            Path path = WorkspacePathGuard.validatePath(filePath);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("HTML file not found: " + filePath);
            }
            if (Files.isDirectory(path)) {
                throw new IllegalArgumentException("Path is a directory, not a file: " + filePath);
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        return inlineHtml;
    }

    private String validateAiNewsEvidenceBoundary(String html, @Nullable ToolContext ctx) {
        if (aiNewsEvidenceBoundaryService == null) return null;
        ChatOrigin origin = ChatOrigin.from(ctx);
        String conversationId = origin == null ? null : origin.conversationId();
        String prefix = "ai-news-event-";
        if (conversationId == null || !conversationId.startsWith(prefix)) return null;
        try {
            long eventId = Long.parseLong(conversationId.substring(prefix.length()));
            long workspaceId = origin.workspaceId() == null ? 1L : origin.workspaceId();
            AiNewsEvidenceBoundaryService.ValidationResult result =
                    aiNewsEvidenceBoundaryService.validate(workspaceId, eventId, html);
            return result.allowed() ? null : result.violationSummary();
        } catch (Exception e) {
            return "无法读取 AI 动态事件证据：" + e.getMessage();
        }
    }

    private static boolean isAiNewsContext(@Nullable ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        return origin != null && origin.conversationId() != null
                && origin.conversationId().startsWith("ai-news-event-");
    }

    private byte[] renderToPng(String source, int viewportWidth, int viewportHeight,
                               boolean fullPage, boolean validateAiNewsLayout) {
        // Playwright's Java client multiplexes all commands through one driver
        // connection and is not thread-safe.  Team runs may request several
        // cards in one tool batch, so serialize the complete browser lifecycle
        // rather than only synchronizing instance creation.  Without this
        // boundary concurrent close/newPage calls intermittently fail with
        // "Object doesn't exist" and leave the task in progress forever.
        synchronized (playwrightLock) {
            Playwright pw = getOrCreatePlaywright();
            BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(BrowserLauncher.chromiumLaunchArgs());
            Browser browser = pw.chromium().launch(opts);
            try {
                BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                        .setViewportSize(viewportWidth, viewportHeight)
                        .setDeviceScaleFactor(2.0));
                try {
                    Page page = ctx.newPage();
                    page.setContent(source, new Page.SetContentOptions()
                            .setWaitUntil(WaitUntilState.NETWORKIDLE)
                            .setTimeout(SET_CONTENT_TIMEOUT_MS));
                    if (validateAiNewsLayout && !fullPage) {
                        List<String> problems = layoutProblems(page.evaluate(AI_NEWS_LAYOUT_CHECK));
                        if (!problems.isEmpty()) {
                            throw new IllegalStateException("AI-news card layout rejected: "
                                    + String.join("；", problems)
                                    + "。请缩短文案、拆分卡片或使用全宽模型名区域后重试");
                        }
                    }
                    return page.screenshot(new Page.ScreenshotOptions()
                            .setFullPage(fullPage)
                            .setType(ScreenshotType.PNG));
                } finally {
                    try { ctx.close(); } catch (Exception ignored) {}
                }
            } finally {
                try { browser.close(); } catch (Exception ignored) {}
            }
        }
    }

    static List<String> layoutProblems(Object raw) {
        if (!(raw instanceof List<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                result.add(String.valueOf(value));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Lazily create one Playwright instance per JVM. Playwright.create()
     * spawns a Node.js child process and costs ~1–2 s; keeping the instance
     * around means subsequent screenshots only pay the browser-launch cost.
     */
    private Playwright getOrCreatePlaywright() {
        Playwright local = sharedPlaywright;
        if (local != null) return local;
        synchronized (playwrightLock) {
            if (sharedPlaywright == null) {
                sharedPlaywright = Playwright.create();
            }
            return sharedPlaywright;
        }
    }

    private static int clampViewport(Integer requested, int fallback) {
        if (requested == null || requested <= 0) return fallback;
        return Math.min(requested, MAX_VIEWPORT_DIMENSION);
    }
}

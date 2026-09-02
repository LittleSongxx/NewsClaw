package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsCaptureStatus;
import vip.newsclaw.news.model.AiNewsEvidenceCaptureTrace;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEvidenceRequest;
import vip.newsclaw.news.model.AiNewsEventEntity;

import java.nio.charset.StandardCharsets;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures a public official page as evidence without browser automation.
 *
 * <p>The service has no mutation capability beyond adding the evidence packet:
 * no form submits, clicks, authentication/cookies, uploads, CDP attachment or
 * JavaScript evaluation. It cannot promote an event to verified.</p>
 */
@Service
public class OfficialSourceEvidenceCaptureService {

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style|noscript)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private final OfficialSourceHttpFetcher httpFetcher;
    private final AiNewsOfficialCaptureProperties properties;
    private final AiNewsEventService eventService;
    private final ObjectMapper objectMapper;
    private final AiNewsSourceRegistry sourceRegistry;
    private final AiNewsCaptureAttemptService captureAttemptService;
    private final AiNewsSourceDocumentParser documentParser;

    @Autowired
    public OfficialSourceEvidenceCaptureService(OfficialSourceHttpFetcher httpFetcher,
                                                AiNewsOfficialCaptureProperties properties,
                                                AiNewsEventService eventService,
                                                ObjectMapper objectMapper,
                                                AiNewsSourceRegistry sourceRegistry,
                                                AiNewsCaptureAttemptService captureAttemptService,
                                                AiNewsSourceDocumentParser documentParser) {
        this.httpFetcher = httpFetcher;
        this.properties = properties;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.sourceRegistry = sourceRegistry;
        this.captureAttemptService = captureAttemptService;
        this.documentParser = documentParser;
    }

    /** Compatibility constructor retained for focused service tests/extensions. */
    public OfficialSourceEvidenceCaptureService(OfficialSourceHttpFetcher httpFetcher,
                                                AiNewsOfficialCaptureProperties properties,
                                                AiNewsEventService eventService,
                                                ObjectMapper objectMapper,
                                                AiNewsSourceRegistry sourceRegistry,
                                                AiNewsCaptureAttemptService captureAttemptService) {
        this(httpFetcher, properties, eventService, objectMapper, sourceRegistry,
                captureAttemptService, new AiNewsSourceDocumentParser(objectMapper));
    }

    /** Narrow constructor retained for transport-boundary unit tests. */
    public OfficialSourceEvidenceCaptureService(OfficialSourceHttpFetcher httpFetcher,
                                                AiNewsOfficialCaptureProperties properties,
                                                AiNewsEventService eventService,
                                                ObjectMapper objectMapper) {
        this(httpFetcher, properties, eventService, objectMapper,
                new AiNewsSourceRegistry(), null, new AiNewsSourceDocumentParser(objectMapper));
    }

    public AiNewsEvidenceEntity capture(Long workspaceId, Long eventId, String sourceUrl, String claim) {
        if (!properties.isEnabled()) {
            throw new NewsClawException(403, "官方来源只读抓取已被部署配置禁用");
        }
        if (sourceUrl == null || sourceUrl.isBlank() || claim == null || claim.isBlank()) {
            throw new NewsClawException(400, "sourceUrl 和 claim 不能为空");
        }
        if (!sourceRegistry.isOfficialUrl(sourceUrl)) {
            throw new NewsClawException(400, "只允许抓取来源注册表中的官方域名");
        }
        AiNewsEventEntity event = eventService.findEvent(workspaceId, eventId);
        try {
            OfficialSourceHttpFetcher.FetchResult fetched = httpFetcher.fetch(sourceUrl.trim(),
                    properties.getMaxBytes(), properties.getTimeoutSeconds(), properties.getMaxRedirects());
            if (fetched.httpStatus() < 200 || fetched.httpStatus() >= 300) {
                AiNewsCaptureStatus status = classifyHttpStatus(fetched.httpStatus());
                record(event, sourceUrl, fetched.finalUrl(), status,
                        httpFailureMessage(status, fetched.httpStatus()), fetched.httpStatus(), fetched.redirectChain());
                throw new NewsClawException(409, httpFailureMessage(status, fetched.httpStatus()));
            }
            if (!sourceRegistry.isOfficialUrl(fetched.finalUrl())) {
                record(event, sourceUrl, fetched.finalUrl(), AiNewsCaptureStatus.REDIRECT_REJECTED,
                        "重定向目标不在官方来源注册表中", fetched.httpStatus(), fetched.redirectChain());
                throw new NewsClawException(409, "重定向目标不是受信任的官方来源");
            }
            AiNewsSourceDocumentParser.ParsedDocument parsed = documentParser.parse(
                    fetched.body(), fetched.contentType(), fetched.finalUrl());
            if (fetched.body() == null || fetched.body().isBlank()
                    || parsed.text() == null || parsed.text().isBlank()) {
                record(event, sourceUrl, fetched.finalUrl(), AiNewsCaptureStatus.EMPTY_CONTENT,
                        "官方页面响应成功，但未提取到可核验正文", fetched.httpStatus(), fetched.redirectChain());
                throw new NewsClawException(409, "官方来源页面为空，不能作为核验证据");
            }
            String title = trim(parsed.title(), 512);
            String quote = trim(parsed.text(), 1_200);
            AiNewsEvidenceRequest request = new AiNewsEvidenceRequest(sourceUrl.trim(), title,
                    parsed.publishedAtUtc(),
                    "official", claim.trim(), quote, 0.8D);
            AiNewsEvidenceCaptureTrace trace = new AiNewsEvidenceCaptureTrace(fetched.finalUrl(),
                    fetched.fetchedAt() == null ? LocalDateTime.now() : fetched.fetchedAt(),
                    sha256(fetched.body()), fetched.httpStatus(), "READ_ONLY_HTTP",
                    objectMapper.writeValueAsString(fetched.redirectChain() == null ? List.of() : fetched.redirectChain()));
            AiNewsEvidenceEntity evidence = eventService.attachCapturedOfficialEvidence(
                    event.getWorkspaceId(), event.getId(), request, trace);
            record(event, sourceUrl, fetched.finalUrl(), AiNewsCaptureStatus.SUCCESS,
                    null, fetched.httpStatus(), fetched.redirectChain());
            return evidence;
        } catch (NewsClawException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            record(event, sourceUrl, null, AiNewsCaptureStatus.TIMEOUT,
                    safeMessage(e), null, List.of());
            throw new NewsClawException(504, "官方来源抓取超时");
        } catch (SecurityException e) {
            record(event, sourceUrl, null, AiNewsCaptureStatus.REDIRECT_REJECTED,
                    safeMessage(e), null, List.of());
            throw new NewsClawException(400, "官方来源 URL 安全校验失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record(event, sourceUrl, null, AiNewsCaptureStatus.NETWORK_ERROR,
                    "抓取线程被中断", null, List.of());
            throw new NewsClawException(502, "官方来源抓取被中断");
        } catch (Exception e) {
            AiNewsCaptureStatus status = isRedirectFailure(e)
                    ? AiNewsCaptureStatus.REDIRECT_REJECTED : AiNewsCaptureStatus.NETWORK_ERROR;
            record(event, sourceUrl, null, status, safeMessage(e), null, List.of());
            throw new NewsClawException(502, "官方来源抓取失败: " + safeMessage(e));
        }
    }

    private void record(AiNewsEventEntity event, String sourceUrl, String finalUrl,
                        AiNewsCaptureStatus status, String error, Integer httpStatus,
                        List<String> redirects) {
        if (captureAttemptService == null || event == null) return;
        try {
            captureAttemptService.record(event.getWorkspaceId(), event.getId(), sourceUrl,
                    finalUrl, status, error, httpStatus, redirects);
        } catch (Exception ignored) {
            // Capture audit is deliberately best-effort and must not replace the
            // original transport outcome with a persistence error.
        }
    }

    private static AiNewsCaptureStatus classifyHttpStatus(int status) {
        if (status == 401 || status == 403 || status == 429) return AiNewsCaptureStatus.BLOCKED;
        if (status == 404 || status == 410) return AiNewsCaptureStatus.NOT_FOUND;
        if (status == 408 || status == 504) return AiNewsCaptureStatus.TIMEOUT;
        if (status >= 300 && status < 400) return AiNewsCaptureStatus.REDIRECT_REJECTED;
        return AiNewsCaptureStatus.NETWORK_ERROR;
    }

    private static String httpFailureMessage(AiNewsCaptureStatus status, int httpStatus) {
        return switch (status) {
            case BLOCKED -> "官方来源返回 HTTP " + httpStatus + "，抓取被站点阻断，不能据此判断官方未发布";
            case NOT_FOUND -> "官方来源返回 HTTP " + httpStatus + "，目标页面不存在";
            case TIMEOUT -> "官方来源返回 HTTP " + httpStatus + "，请求超时";
            case REDIRECT_REJECTED -> "官方来源返回 HTTP " + httpStatus + "，重定向未被接受";
            default -> "官方来源返回 HTTP " + httpStatus + "，抓取失败";
        };
    }

    private static boolean isRedirectFailure(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("重定向");
    }

    static String extractTitle(String html) {
        if (html == null || html.isBlank()) return "官方来源页面";
        Matcher matcher = TITLE.matcher(html);
        return trim(text(matcher.find() ? matcher.group(1) : "官方来源页面"), 512);
    }

    static String extractExcerpt(String html) {
        return trim(text(html), 1_200);
    }

    private static String text(String html) {
        String withoutExecutable = SCRIPT_STYLE.matcher(html == null ? "" : html).replaceAll(" ");
        String plain = TAG.matcher(withoutExecutable).replaceAll(" ");
        return SPACE.matcher(decodeBasicEntities(plain)).replaceAll(" ").trim();
    }

    private static String decodeBasicEntities(String value) {
        return value.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return "官方来源页面";
        return value.length() <= max ? value : value.substring(0, max).trim();
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.substring(0, Math.min(300, message.length()));
    }
}

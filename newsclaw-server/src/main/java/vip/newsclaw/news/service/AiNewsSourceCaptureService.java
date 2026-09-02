package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsSourceCaptureEntity;
import vip.newsclaw.news.repository.AiNewsSourceCaptureMapper;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import vip.newsclaw.trigger.ingest.TriggerRateLimiter;

/**
 * Creates and reads immutable source snapshots used by Agent-originated news
 * evidence. Capturing and attaching are separate operations: an Agent first
 * receives a capture id, reads bounded pages from that server-owned snapshot,
 * then quotes text which the service locates exactly before event insertion.
 */
@Service
@Slf4j
public class AiNewsSourceCaptureService {

    static final int CAPTURE_EXCERPT_CHARS = 5_000;
    static final int READ_PAGE_CHARS = 6_000;
    static final int MIN_QUOTE_CHARS = 12;
    static final Duration MAX_ADMISSION_WINDOW = Duration.ofDays(31);

    private final OfficialSourceHttpFetcher httpFetcher;
    private final AiNewsOfficialCaptureProperties properties;
    private final AiNewsSourceRegistry sourceRegistry;
    private final AiNewsSourceDocumentParser documentParser;
    private final AiNewsSourceCaptureMapper captureMapper;
    private final ObjectMapper objectMapper;
    private final AiNewsSourceTimeAttestationService timeAttestationService;
    private final TriggerRateLimiter rateLimiter = new TriggerRateLimiter();
    private final ConcurrentHashMap<Long, Semaphore> concurrency = new ConcurrentHashMap<>();

    @Autowired
    public AiNewsSourceCaptureService(OfficialSourceHttpFetcher httpFetcher,
                                      AiNewsOfficialCaptureProperties properties,
                                      AiNewsSourceRegistry sourceRegistry,
                                      AiNewsSourceDocumentParser documentParser,
                                      AiNewsSourceCaptureMapper captureMapper,
                                      ObjectMapper objectMapper,
                                      AiNewsSourceTimeAttestationService timeAttestationService) {
        this.httpFetcher = httpFetcher;
        this.properties = properties;
        this.sourceRegistry = sourceRegistry;
        this.documentParser = documentParser;
        this.captureMapper = captureMapper;
        this.objectMapper = objectMapper;
        this.timeAttestationService = timeAttestationService;
    }

    /** Compatibility constructor for focused unit tests and extension code. */
    AiNewsSourceCaptureService(OfficialSourceHttpFetcher httpFetcher,
                               AiNewsOfficialCaptureProperties properties,
                               AiNewsSourceRegistry sourceRegistry,
                               AiNewsSourceDocumentParser documentParser,
                               AiNewsSourceCaptureMapper captureMapper,
                               ObjectMapper objectMapper) {
        this(httpFetcher, properties, sourceRegistry, documentParser, captureMapper,
                objectMapper, null);
    }

    public CaptureSummary capture(Long workspaceId, String sourceUrl) {
        long workspace = workspace(workspaceId);
        if (!properties.isEnabled()) {
            throw new NewsClawException(403, "AI 动态来源只读抓取已被部署配置禁用");
        }
        String requestedUrl = validateSourceUrl(sourceUrl);
        int rate = Math.max(0, Math.min(properties.getMaxCapturesPerMinute(), 10_000));
        if (!rateLimiter.tryAcquire(workspace, rate, Instant.now())) {
            throw new NewsClawException(429, "当前 workspace 来源抓取过于频繁，请稍后重试");
        }
        int permits = Math.max(1, Math.min(properties.getMaxConcurrentCaptures(), 20));
        Semaphore permit = concurrency.computeIfAbsent(workspace, ignored -> new Semaphore(permits));
        if (!permit.tryAcquire()) {
            throw new NewsClawException(429, "当前 workspace 来源抓取并发已满，请稍后重试");
        }
        try {
        int reuseMinutes = Math.max(0, Math.min(properties.getReuseTtlMinutes(), 7 * 24 * 60));
        LocalDateTime reusableAfter = LocalDateTime.now().minusMinutes(reuseMinutes);
        AiNewsSourceCaptureEntity reusable = reuseMinutes == 0 ? null : captureMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiNewsSourceCaptureEntity>()
                        .eq(AiNewsSourceCaptureEntity::getWorkspaceId, workspace)
                        .eq(AiNewsSourceCaptureEntity::getSourceUrlHash, sha256(requestedUrl))
                        .eq(AiNewsSourceCaptureEntity::getCaptureStatus, "success")
                        .ge(AiNewsSourceCaptureEntity::getFetchedAt, reusableAfter)
                        .eq(!properties.isAllowExtractionFallback(),
                                AiNewsSourceCaptureEntity::getExtractionFallback, 0)
                        .eq(AiNewsSourceCaptureEntity::getDeleted, 0)
                        .orderByDesc(AiNewsSourceCaptureEntity::getFetchedAt)
                        .last("LIMIT 1"));
        if (reusable != null && reusable.getFetchedAt() != null
                && !reusable.getFetchedAt().isBefore(reusableAfter)) {
            // A successful immutable snapshot is safe to reuse. Re-fetching
            // the same URL for every candidate creates duplicate evidence
            // rows and makes capture-rate denominators depend on retries.
            return summary(reusable);
        }
        AiNewsSourceCaptureEntity row = baseRow(workspace, requestedUrl);
        try {
            OfficialSourceHttpFetcher.FetchResult fetched = fetchWithRetry(requestedUrl);
            applyTransport(row, fetched);
            if (fetched.httpStatus() < 200 || fetched.httpStatus() >= 300) {
                row.setCaptureStatus("http_error");
                row.setCaptureError("HTTP " + fetched.httpStatus());
                persist(row);
                throw new NewsClawException(409,
                        "来源抓取返回 HTTP " + fetched.httpStatus() + "，不能创建证据 capture");
            }
            if (!fetched.bodyComplete()) {
                row.setCaptureStatus("body_too_large");
                row.setCaptureError("响应正文超过 " + properties.getMaxBytes() + " bytes 上限");
                persist(row);
                throw new NewsClawException(413,
                        "来源响应正文超过部署上限，未截断入库或送入正文抽取");
            }
            AiNewsSourceDocumentParser.ParsedDocument parsed = documentParser.parse(
                    fetched.body(), fetched.contentType(), fetched.finalUrl());
            if (parsed.text() == null || parsed.text().isBlank()) {
                row.setCaptureStatus("empty_content");
                row.setCaptureError("未提取到可核验正文");
                persist(row);
                throw new NewsClawException(409, "来源页面没有可核验正文，不能创建证据 capture");
            }
            row.setSourceTitle(trim(parsed.title(), 512));
            applyPublicationTime(row, parsed, fetched.finalUrl());
            row.setExtractedText(parsed.text());
            row.setExtractedTextHash(sha256(parsed.text()));
            row.setTextLength(parsed.text().length());
            row.setExtractorName(trim(parsed.extractorName(), 64));
            row.setExtractorVersion(trim(parsed.extractorVersion(), 64));
            row.setExtractorConfigHash(trim(parsed.extractorConfigHash(), 64));
            row.setExtractionFallback(parsed.extractionFallback() ? 1 : 0);
            row.setExtractionWarning(trim(parsed.extractionWarning(), 512));
            if (parsed.extractionFallback() && !properties.isAllowExtractionFallback()) {
                row.setCaptureStatus("extraction_fallback_rejected");
                row.setCaptureError("正文来自兼容性 fallback，未达到主正文抽取证据门禁");
                persist(row);
                throw new NewsClawException(409,
                        "正文抽取使用了兼容性 fallback，不能创建可发布证据 capture");
            }
            int minimumTextChars = Math.max(1, Math.min(properties.getMinTextChars(), 5_000));
            int extractedCharacters = parsed.text().codePointCount(0, parsed.text().length());
            if (extractedCharacters < minimumTextChars) {
                row.setCaptureStatus("insufficient_content");
                row.setCaptureError("规范化正文只有 " + extractedCharacters
                        + " 字符，低于 " + minimumTextChars + " 字符门禁");
                persist(row);
                throw new NewsClawException(409,
                        "来源正文过短，不能创建可核验证据 capture");
            }
            row.setCaptureStatus("success");
            row.setCaptureError(null);
            persist(row);
            return summary(row);
        } catch (NewsClawException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            persistFailure(row, "timeout", safeMessage(e));
            throw new NewsClawException(504, "来源抓取超时，不能创建证据 capture");
        } catch (SecurityException | IllegalArgumentException e) {
            persistFailure(row, "rejected", safeMessage(e));
            throw new NewsClawException(400, "来源 URL 安全校验失败: " + safeMessage(e));
        } catch (AiNewsContentExtractionException e) {
            persistFailure(row, "extraction_error", safeMessage(e));
            throw new NewsClawException(502, "来源正文抽取失败: " + safeMessage(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            persistFailure(row, "network_error", "抓取线程被中断");
            throw new NewsClawException(502, "来源抓取被中断");
        } catch (Exception e) {
            persistFailure(row, "network_error", safeMessage(e));
            throw new NewsClawException(502, "来源抓取失败: " + safeMessage(e));
        }
        } finally {
            permit.release();
        }
    }

    public CapturePage read(Long workspaceId, Long captureId, Integer requestedOffset) {
        AiNewsSourceCaptureEntity row = requireSuccessful(workspaceId, captureId);
        String text = row.getExtractedText() == null ? "" : row.getExtractedText();
        int offset = requestedOffset == null ? 0 : requestedOffset;
        if (offset < 0 || offset > text.length()) {
            throw new NewsClawException(400, "startOffset 必须在 0 到 " + text.length() + " 之间");
        }
        int end = safeEnd(text, offset, Math.min(text.length(), offset + READ_PAGE_CHARS));
        String content = text.substring(offset, end);
        return new CapturePage(String.valueOf(row.getId()), offset, end, text.length(),
                content, end < text.length(), end < text.length() ? end : null,
                utc(row.getSourcePublishedAt()), row.getContentHash(),
                row.getExtractorName(), row.getExtractorVersion(), row.getExtractorConfigHash(),
                row.getExtractionFallback() != null && row.getExtractionFallback() != 0);
    }

    /**
     * Validate one Agent quote against the immutable capture and, when a
     * discovery window is supplied, fail closed on missing/out-of-window source
     * publication metadata.
     */
    public BoundCapture bind(Long workspaceId, Long captureId, String quote,
                             Instant windowStart, Instant windowEnd) {
        AiNewsSourceCaptureEntity row = requireSuccessful(workspaceId, captureId);
        validateWindow(windowStart, windowEnd);
        validateStructuredTimeAttestation(row);
        if ((windowStart != null || windowEnd != null) && row.getSourcePublishedAt() == null) {
            throw new NewsClawException(409,
                    "capture 缺少带时区的来源发布时间，不能进入最新新闻窗口");
        }
        if (windowStart != null) {
            Instant published = row.getSourcePublishedAt().toInstant(ZoneOffset.UTC);
            if (published.isBefore(windowStart) || !published.isBefore(windowEnd)) {
                throw new NewsClawException(409,
                        "来源发布时间 " + published + " 不在要求窗口 ["
                                + windowStart + ", " + windowEnd + ") 内");
            }
        }

        String normalizedQuote = AiNewsSourceDocumentParser.normalizeText(quote);
        if (normalizedQuote.codePointCount(0, normalizedQuote.length()) < MIN_QUOTE_CHARS) {
            throw new NewsClawException(400,
                    "quote 至少需要 " + MIN_QUOTE_CHARS + " 个字符，且必须逐字来自 capture 正文");
        }
        String text = row.getExtractedText() == null ? "" : row.getExtractedText();
        int start = text.indexOf(normalizedQuote);
        if (start < 0) {
            throw new NewsClawException(409,
                    "quote 无法在 capture 正文中精确定位；请用 read_capture 返回的原文重新引用"
                            + quoteMismatchHint(text, normalizedQuote));
        }
        int end = start + normalizedQuote.length();
        return new BoundCapture(row, text.substring(start, end), start, end,
                "NORMALIZED_EXACT");
    }

    private AiNewsSourceCaptureEntity requireSuccessful(Long workspaceId, Long captureId) {
        if (captureId == null || captureId <= 0) {
            throw new NewsClawException(400, "captureId 必须是有效数字 ID");
        }
        AiNewsSourceCaptureEntity row = captureMapper.selectById(captureId);
        if (row == null || row.getDeleted() != null && row.getDeleted() != 0
                || row.getWorkspaceId() == null || row.getWorkspaceId() != workspace(workspaceId)) {
            throw new NewsClawException(404,
                    "来源 capture 不存在或不属于当前 workspace；captureId 必须逐字复制"
                            + " capture_source 的成功响应，不能按调用顺序推算或改写");
        }
        if (!"success".equalsIgnoreCase(row.getCaptureStatus())
                || row.getHttpStatus() == null || row.getHttpStatus() < 200 || row.getHttpStatus() >= 300
                || row.getFetchedAt() == null || blank(row.getFinalUrl())
                || blank(row.getContentHash()) || blank(row.getCaptureMethod())
                || blank(row.getExtractedText()) || blank(row.getExtractedTextHash())
                || blank(row.getExtractorName()) || blank(row.getExtractorVersion())
                || blank(row.getExtractorConfigHash())
                || !sha256(row.getExtractedText()).equals(row.getExtractedTextHash())) {
            throw new NewsClawException(409, "来源 capture 不完整，不能绑定为事件证据");
        }
        if (row.getExtractionFallback() != null && row.getExtractionFallback() != 0
                && !properties.isAllowExtractionFallback()) {
            throw new NewsClawException(409,
                    "来源 capture 使用了兼容性正文抽取，当前部署不允许作为发布证据");
        }
        return row;
    }

    private void applyTransport(AiNewsSourceCaptureEntity row,
                                OfficialSourceHttpFetcher.FetchResult fetched) throws Exception {
        row.setFinalUrl(trim(fetched.finalUrl(), 4096));
        row.setHttpStatus(fetched.httpStatus());
        row.setFetchedAt(fetched.fetchedAt() == null ? LocalDateTime.now() : fetched.fetchedAt());
        row.setContentHash(sha256(fetched.body()));
        row.setContentType(trim(fetched.contentType(), 256));
        row.setCaptureMethod("proxy_fallback".equalsIgnoreCase(fetched.transportRoute())
                ? "READ_ONLY_HTTP_PROXY_FALLBACK" : "READ_ONLY_HTTP");
        row.setRedirectChainJson(objectMapper.writeValueAsString(
                fetched.redirectChain() == null ? List.of() : fetched.redirectChain()));
        row.setSourceTier(sourceTier(fetched.finalUrl()));
    }

    /**
     * Retry only failures that can plausibly be transient. The policy is
     * deliberately bounded because this method runs synchronously in an Agent
     * tool call. A server-provided Retry-After is honoured when it fits inside
     * that budget; it is never shortened into an early retry.
     */
    private OfficialSourceHttpFetcher.FetchResult fetchWithRetry(String sourceUrl) throws Exception {
        int attempts = Math.max(1, Math.min(properties.getMaxAttempts(), 4));
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                OfficialSourceHttpFetcher.FetchResult fetched = httpFetcher.fetch(sourceUrl,
                        properties.getMaxBytes(), properties.getTimeoutSeconds(), properties.getMaxRedirects());
                if (attempt >= attempts || !retryableStatus(fetched.httpStatus())) return fetched;
                long delay = retryDelayMillis(attempt, fetched.retryAfter());
                if (delay < 0) return fetched;
                pause(delay);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (attempt >= attempts || !retryableTransportFailure(e)) throw e;
                pause(retryDelayMillis(attempt, null));
            }
        }
        throw new IllegalStateException("来源抓取重试状态异常");
    }

    private static boolean retryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500 && status <= 599;
    }

    private static boolean retryableTransportFailure(Exception error) {
        return error instanceof HttpTimeoutException || error instanceof IOException;
    }

    /** Returns -1 when Retry-After exceeds the bounded synchronous budget. */
    private long retryDelayMillis(int attempt, String retryAfter) {
        long maximum = Math.max(0L, Math.min(properties.getRetryMaxDelayMillis(), 30_000));
        Long requested = parseRetryAfterMillis(retryAfter);
        if (requested != null) return requested > maximum ? -1L : requested;
        long base = Math.max(0L, Math.min(properties.getRetryBaseDelayMillis(), maximum));
        long exponentialBound = Math.min(maximum, base * (1L << Math.min(10, Math.max(0, attempt - 1))));
        return exponentialBound <= 0 ? 0 : ThreadLocalRandom.current().nextLong(exponentialBound + 1);
    }

    private static Long parseRetryAfterMillis(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        try {
            return Math.multiplyExact(Long.parseLong(value), 1_000L);
        } catch (Exception ignored) {
            try {
                return Math.max(0L, Duration.between(Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());
            } catch (Exception invalidDate) {
                return null;
            }
        }
    }

    private static void pause(long delayMillis) throws InterruptedException {
        if (delayMillis > 0) Thread.sleep(delayMillis);
    }

    private AiNewsSourceCaptureEntity baseRow(long workspaceId, String sourceUrl) {
        AiNewsSourceCaptureEntity row = new AiNewsSourceCaptureEntity();
        row.setWorkspaceId(workspaceId);
        row.setSourceUrl(sourceUrl);
        row.setSourceUrlHash(sha256(sourceUrl));
        row.setSourceTier(sourceTier(sourceUrl));
        row.setCaptureMethod("READ_ONLY_HTTP");
        row.setSourceTimeOrigin("NONE");
        row.setSourceTimeAttestationStatus("NOT_ATTEMPTED");
        row.setCaptureStatus("started");
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        row.setDeleted(0);
        return row;
    }

    private String sourceTier(String url) {
        if (sourceRegistry.isOfficialUrl(url)) return "official";
        if (sourceRegistry.isTrustedMediaUrl(url)) return "media";
        return "community";
    }

    private void applyPublicationTime(AiNewsSourceCaptureEntity row,
                                      AiNewsSourceDocumentParser.ParsedDocument parsed,
                                      String finalUrl) {
        if (parsed.publishedAtUtc() != null) {
            row.setSourcePublishedAt(parsed.publishedAtUtc());
            row.setPublishedAtRaw(trim(parsed.publishedAtRaw(), 512));
            row.setPublishedAtMethod(trim(parsed.publishedAtMethod(), 64));
            row.setSourceTimeOrigin("PAGE_METADATA");
            row.setSourceTimeAttestationStatus("NOT_REQUIRED");
            return;
        }
        row.setSourceTimeOrigin("NONE");
        if (timeAttestationService == null) {
            row.setSourceTimeAttestationStatus("NOT_CONFIGURED");
            return;
        }
        try {
            AiNewsSourceTimeAttestationService.Resolution resolution =
                    timeAttestationService.resolve(finalUrl);
            row.setSourceTimeAttestationStatus(trim(resolution.status(), 32));
            AiNewsSourceTimeAttestationService.Attestation attestation = resolution.attestation();
            if (attestation == null) return;
            row.setSourcePublishedAt(attestation.publishedAtUtc());
            row.setPublishedAtRaw(trim(attestation.publishedAtRaw(), 512));
            row.setPublishedAtMethod(trim(attestation.method(), 64));
            row.setSourceTimeOrigin("STRUCTURED_SOURCE");
            row.setSourceTimeItemVersionId(attestation.sourceItemVersionId());
            row.setSourceTimeAttestationHash(trim(attestation.attestationHash(), 64));
        } catch (Exception error) {
            // Body capture remains useful for review, but publication time stays
            // absent so the latest-news admission path still fails closed.
            row.setSourceTimeAttestationStatus("ERROR");
            log.warn("Structured source time attestation failed closed for {}: {}",
                    finalUrl, safeMessage(error));
        }
    }

    private void validateStructuredTimeAttestation(AiNewsSourceCaptureEntity row) {
        if (!"STRUCTURED_SOURCE".equalsIgnoreCase(row.getSourceTimeOrigin())) return;
        if (timeAttestationService == null) {
            throw new NewsClawException(409, "结构化来源发布时间证据当前不可复核，不能进入最新新闻窗口");
        }
        AiNewsSourceTimeAttestationService.Validation validation = timeAttestationService.validate(
                row.getFinalUrl(), row.getSourceTimeItemVersionId(),
                row.getSourceTimeAttestationHash(), row.getSourcePublishedAt(),
                row.getPublishedAtRaw());
        if (!validation.valid()) {
            throw new NewsClawException(409, "结构化来源发布时间证据复核失败（"
                    + validation.status() + "），不能进入最新新闻窗口");
        }
    }

    private void persistFailure(AiNewsSourceCaptureEntity row, String status, String error) {
        row.setCaptureStatus(status);
        row.setCaptureError(trim(error, 2000));
        try {
            persist(row);
        } catch (Exception ignored) {
            // Preserve the transport outcome even when best-effort audit
            // persistence itself fails.
        }
    }

    private void persist(AiNewsSourceCaptureEntity row) {
        row.setUpdateTime(LocalDateTime.now());
        if (row.getId() == null) captureMapper.insert(row);
        else captureMapper.updateById(row);
    }

    private static CaptureSummary summary(AiNewsSourceCaptureEntity row) {
        String text = row.getExtractedText() == null ? "" : row.getExtractedText();
        int end = safeEnd(text, 0, Math.min(text.length(), CAPTURE_EXCERPT_CHARS));
        return new CaptureSummary(String.valueOf(row.getId()), row.getSourceUrl(), row.getFinalUrl(),
                row.getSourceTitle(), utc(row.getSourcePublishedAt()), row.getPublishedAtMethod(),
                row.getSourceTimeOrigin(), row.getSourceTimeAttestationStatus(),
                row.getSourceTimeItemVersionId(), row.getSourceTimeAttestationHash(),
                row.getSourceTier(), row.getCaptureMethod(), row.getHttpStatus(),
                row.getFetchedAt(), row.getContentHash(),
                row.getExtractedTextHash(), row.getExtractorName(), row.getExtractorVersion(),
                row.getExtractorConfigHash(),
                row.getExtractionFallback() != null && row.getExtractionFallback() != 0,
                row.getExtractionWarning(), text.length(), text.substring(0, end), end < text.length(),
                end < text.length() ? end : null,
                "excerpt 本身就是可直接逐字引用的 capture 正文；仅当所需原文不在 excerpt 且"
                        + " truncated=true 时才按 nextOffset 调 read_capture。必须逐字复制 captureId，"
                        + "并对每个 URL 串行完成 capture→必要的 read→upsert，禁止并行批量抓取后推算 ID");
    }

    private static void validateWindow(Instant start, Instant end) {
        if (start == null && end == null) return;
        if (start == null || end == null || !start.isBefore(end)) {
            throw new NewsClawException(400, "windowStart/windowEnd 必须同时提供，且 start 早于 end");
        }
        if (end.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw new NewsClawException(400, "windowEnd 不能明显晚于当前时间");
        }
        if (Duration.between(start, end).compareTo(MAX_ADMISSION_WINDOW) > 0) {
            throw new NewsClawException(400, "最新新闻入库窗口不能超过 31 天");
        }
    }

    private static String validateSourceUrl(String value) {
        if (value == null || value.isBlank()) throw new NewsClawException(400, "sourceUrl 不能为空");
        try {
            URI uri = URI.create(value.trim());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("not absolute HTTP(S)");
            }
            return AiNewsEventService.canonicalUrl(value.trim());
        } catch (Exception e) {
            throw new NewsClawException(400, "sourceUrl 必须是有效的绝对 http/https URL");
        }
    }

    private static int safeEnd(String text, int start, int desiredEnd) {
        int end = Math.max(start, Math.min(text.length(), desiredEnd));
        if (end > start && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    /**
     * Diagnostic only: locate a shared exact anchor and return a bounded public
     * source excerpt. It never changes the fail-closed admission decision.
     */
    private static String quoteMismatchHint(String text, String quote) {
        if (blank(text) || blank(quote)) return "";
        List<String> anchors = new java.util.ArrayList<>();
        java.util.Arrays.stream(quote.split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 8)
                .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                .limit(8).forEach(anchors::add);
        int chunk = Math.min(32, quote.length());
        if (chunk >= 12) {
            anchors.add(quote.substring(0, chunk));
            int middle = Math.max(0, (quote.length() - chunk) / 2);
            anchors.add(quote.substring(middle, middle + chunk));
            anchors.add(quote.substring(quote.length() - chunk));
        }
        for (String anchor : anchors) {
            int at = text.indexOf(anchor);
            if (at < 0) continue;
            int contextStart = Math.max(0, at - 100);
            int contextEnd = safeEnd(text, contextStart, Math.min(text.length(), at + anchor.length() + 140));
            String context = text.substring(contextStart, contextEnd)
                    .replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
            return "；最近共享原文位于 offset " + contextStart + "：" + context;
        }
        return "";
    }

    private static long workspace(Long workspaceId) {
        return workspaceId == null || workspaceId <= 0 ? 1L : workspaceId;
    }

    private static String utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max).trim();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String safeMessage(Exception e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? e.getClass().getSimpleName()
                : value.substring(0, Math.min(value.length(), 300));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record CaptureSummary(String captureId,
                                 String sourceUrl,
                                 String finalUrl,
                                 String sourceTitle,
                                 String sourcePublishedAtUtc,
                                 String publishedAtMethod,
                                 String sourceTimeOrigin,
                                 String sourceTimeAttestationStatus,
                                 Long sourceTimeItemVersionId,
                                 String sourceTimeAttestationHash,
                                 String sourceTier,
                                 String captureMethod,
                                 Integer httpStatus,
                                 LocalDateTime fetchedAt,
                                 String contentHash,
                                 String extractedTextHash,
                                 String extractorName,
                                 String extractorVersion,
                                 String extractorConfigHash,
                                 boolean extractionFallback,
                                 String extractionWarning,
                                 int textLength,
                                 String excerpt,
                                 boolean truncated,
                                 Integer nextOffset,
                                 String admissionRule) {
    }

    public record CapturePage(String captureId,
                              int startOffset,
                              int endOffset,
                              int textLength,
                              String content,
                              boolean truncated,
                              Integer nextOffset,
                              String sourcePublishedAtUtc,
                              String contentHash,
                              String extractorName,
                              String extractorVersion,
                              String extractorConfigHash,
                              boolean extractionFallback) {
    }

    public record BoundCapture(AiNewsSourceCaptureEntity capture,
                               String authoritativeQuote,
                               int quoteStart,
                               int quoteEnd,
                               String quoteMatchMethod) {
    }
}

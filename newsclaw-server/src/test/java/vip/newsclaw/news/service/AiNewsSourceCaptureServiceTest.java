package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsSourceCaptureEntity;
import vip.newsclaw.news.repository.AiNewsSourceCaptureMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsSourceCaptureServiceTest {

    private OfficialSourceHttpFetcher fetcher;
    private AiNewsSourceCaptureMapper mapper;
    private AiNewsSourceCaptureService service;
    private final AtomicReference<AiNewsSourceCaptureEntity> inserted = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        fetcher = mock(OfficialSourceHttpFetcher.class);
        mapper = mock(AiNewsSourceCaptureMapper.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        doAnswer(invocation -> {
            AiNewsSourceCaptureEntity row = invocation.getArgument(0);
            row.setId(501L);
            inserted.set(row);
            return 1;
        }).when(mapper).insert(any(AiNewsSourceCaptureEntity.class));
        AiNewsOfficialCaptureProperties properties = new AiNewsOfficialCaptureProperties();
        properties.setMinTextChars(1);
        properties.setAllowExtractionFallback(true);
        service = new AiNewsSourceCaptureService(fetcher, properties,
                new AiNewsSourceRegistry(), new AiNewsSourceDocumentParser(objectMapper), mapper,
                objectMapper);
    }

    @Test
    void capturePersistsImmutableProvenanceAndBindLocatesExactQuote() throws Exception {
        String quote = "OpenAI released Model X to developers worldwide.";
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/model-x", 200,
                        "<html><head><title>Model X</title>"
                                + "<meta property='article:published_time' content='2026-08-26T12:30:00+08:00'>"
                                + "</head><body><p>" + quote + "</p></body></html>",
                        "text/html", LocalDateTime.of(2026, 8, 26, 5, 0),
                        List.of("https://openai.com/news/model-x")));

        AiNewsSourceCaptureService.CaptureSummary summary = service.capture(
                7L, "https://openai.com/news/model-x#tracking");

        assertEquals("501", summary.captureId());
        assertEquals("2026-08-26T04:30:00Z", summary.sourcePublishedAtUtc());
        assertEquals("official", summary.sourceTier());
        assertEquals("PAGE_METADATA", summary.sourceTimeOrigin());
        assertEquals("NOT_REQUIRED", summary.sourceTimeAttestationStatus());
        assertEquals(64, summary.contentHash().length());
        assertEquals(64, summary.extractedTextHash().length());
        assertEquals("jsoup_document_text", summary.extractorName());
        assertEquals(64, summary.extractorConfigHash().length());
        assertTrue(summary.extractionFallback());
        assertTrue(summary.excerpt().contains(quote));

        AiNewsSourceCaptureEntity persisted = inserted.get();
        assertNotNull(persisted);
        when(mapper.selectById(501L)).thenReturn(persisted);
        AiNewsSourceCaptureService.BoundCapture bound = service.bind(7L, 501L, quote,
                Instant.parse("2026-08-26T03:00:00Z"),
                Instant.parse("2026-08-26T05:00:00Z"));

        assertEquals(quote, bound.authoritativeQuote());
        assertEquals("NORMALIZED_EXACT", bound.quoteMatchMethod());
        assertTrue(bound.quoteEnd() > bound.quoteStart());
        ArgumentCaptor<AiNewsSourceCaptureEntity> captor =
                ArgumentCaptor.forClass(AiNewsSourceCaptureEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("READ_ONLY_HTTP", captor.getValue().getCaptureMethod());
        assertEquals(200, captor.getValue().getHttpStatus());
    }

    @Test
    void freshSuccessfulCaptureIsReusedWithoutAnotherNetworkRequest() throws Exception {
        AiNewsSourceCaptureEntity reusable = successfulCapture();
        reusable.setFetchedAt(LocalDateTime.now());
        when(mapper.selectOne(any())).thenReturn(reusable);

        AiNewsSourceCaptureService.CaptureSummary summary = service.capture(
                7L, "https://openai.com/index/model-x");

        assertEquals("501", summary.captureId());
        org.mockito.Mockito.verify(fetcher, org.mockito.Mockito.never())
                .fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    void staleSuccessfulCaptureIsFetchedAgain() throws Exception {
        AiNewsSourceCaptureEntity stale = successfulCapture();
        stale.setFetchedAt(LocalDateTime.now().minusHours(7));
        when(mapper.selectOne(any())).thenReturn(stale);
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/model-x", 200,
                        "<html><head><title>Model X refreshed</title>"
                                + "<meta property='article:published_time' content='2026-08-26T12:30:00+08:00'>"
                                + "</head><body><p>Fresh page body for the new immutable snapshot.</p></body></html>",
                        "text/html", LocalDateTime.now(), List.of()));

        service.capture(7L, "https://openai.com/index/model-x");

        verify(fetcher).fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class));
        verify(mapper).insert(any(AiNewsSourceCaptureEntity.class));
    }

    @Test
    void workspaceCaptureRateLimitRejectsExcessOutboundRequests() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AiNewsOfficialCaptureProperties properties = new AiNewsOfficialCaptureProperties();
        properties.setMinTextChars(1);
        properties.setAllowExtractionFallback(true);
        properties.setReuseTtlMinutes(0);
        properties.setMaxCapturesPerMinute(1);
        AiNewsSourceCaptureService limited = new AiNewsSourceCaptureService(fetcher, properties,
                new AiNewsSourceRegistry(), new AiNewsSourceDocumentParser(objectMapper), mapper,
                objectMapper);
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/model-x", 200,
                        "<html><body><p>Enough fresh evidence text for capture.</p></body></html>",
                        "text/html", LocalDateTime.now(), List.of()));

        limited.capture(7L, "https://openai.com/index/model-x");
        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> limited.capture(7L, "https://openai.com/index/model-y"));

        assertEquals(429, rejected.getCode());
    }

    @Test
    void bindRejectsFabricatedQuoteAndOutOfWindowSource() {
        AiNewsSourceCaptureEntity row = successfulCapture();
        when(mapper.selectById(501L)).thenReturn(row);

        NewsClawException quoteError = assertThrows(NewsClawException.class,
                () -> service.bind(7L, 501L, "This sentence was never on the source page.",
                        Instant.parse("2026-08-26T03:00:00Z"),
                        Instant.parse("2026-08-26T05:00:00Z")));
        assertEquals(409, quoteError.getCode());

        NewsClawException windowError = assertThrows(NewsClawException.class,
                () -> service.bind(7L, 501L, "OpenAI released Model X to developers worldwide.",
                        Instant.parse("2026-08-27T03:00:00Z"),
                        Instant.parse("2026-08-27T05:00:00Z")));
        assertEquals(409, windowError.getCode());
        assertTrue(windowError.getMessage().contains("不在要求窗口"));
    }

    @Test
    void quoteMismatchReturnsBoundedExactNeighborWithoutFuzzyAdmission() {
        AiNewsSourceCaptureEntity row = successfulCapture();
        when(mapper.selectById(501L)).thenReturn(row);

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> service.bind(7L, 501L,
                        "OpenAI released Model Y to developers worldwide.",
                        Instant.parse("2026-08-26T03:00:00Z"),
                        Instant.parse("2026-08-26T05:00:00Z")));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("offset"));
        assertTrue(error.getMessage().contains("OpenAI released Model X"));
    }

    @Test
    void bindRejectsMissingReliablePublicationTimestamp() {
        AiNewsSourceCaptureEntity row = successfulCapture();
        row.setSourcePublishedAt(null);
        when(mapper.selectById(501L)).thenReturn(row);

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> service.bind(7L, 501L, "OpenAI released Model X to developers worldwide.",
                        Instant.parse("2026-08-26T03:00:00Z"),
                        Instant.parse("2026-08-26T05:00:00Z")));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("缺少带时区"));
    }

    @Test
    void readAndBindRejectStoredTextThatNoLongerMatchesItsCaptureHash() {
        AiNewsSourceCaptureEntity row = successfulCapture();
        row.setExtractedText(row.getExtractedText() + " tampered");
        when(mapper.selectById(501L)).thenReturn(row);

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> service.read(7L, 501L, 0));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("capture 不完整"));
    }

    @Test
    void unknownCaptureExplainsThatAgentMustCopyRatherThanInferTheId() {
        when(mapper.selectById(999L)).thenReturn(null);

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> service.read(7L, 999L, 0));

        assertEquals(404, error.getCode());
        assertTrue(error.getMessage().contains("逐字复制"));
        assertTrue(error.getMessage().contains("不能按调用顺序推算"));
    }

    @Test
    void failedHttpCaptureIsPersistedForAttemptMetrics() throws Exception {
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/missing", 429, "rate limited",
                        "text/plain", LocalDateTime.of(2026, 8, 26, 5, 0), List.of()));

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.capture(7L, "https://openai.com/index/missing"));

        assertEquals(409, rejected.getCode());
        assertEquals("http_error", inserted.get().getCaptureStatus());
        assertEquals(429, inserted.get().getHttpStatus());
    }

    @Test
    void retriesTransientTimeoutOnceThenPersistsSuccessfulCapture() throws Exception {
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenThrow(new HttpTimeoutException("temporary timeout"))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/model-x", 200,
                        "<html><head><meta property='article:published_time' content='2026-08-26T04:30:00Z'>"
                                + "</head><body><p>OpenAI released Model X to developers worldwide.</p></body></html>",
                        "text/html", LocalDateTime.of(2026, 8, 26, 5, 0), List.of()));

        AiNewsSourceCaptureService.CaptureSummary summary = service.capture(
                7L, "https://openai.com/index/model-x");

        assertEquals(200, summary.httpStatus());
        verify(fetcher, org.mockito.Mockito.times(2))
                .fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    void doesNotRetryPermanentForbiddenResponse() throws Exception {
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/private", 403, "forbidden", "text/plain",
                        LocalDateTime.of(2026, 8, 26, 5, 0), List.of()));

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.capture(7L, "https://openai.com/index/private"));

        assertEquals(409, rejected.getCode());
        verify(fetcher).fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    void requiredExtractorFailureIsPersistedAndFailsCaptureClosed() throws Exception {
        AiNewsSourceDocumentParser failingParser = mock(AiNewsSourceDocumentParser.class);
        service = new AiNewsSourceCaptureService(fetcher, new AiNewsOfficialCaptureProperties(),
                new AiNewsSourceRegistry(), failingParser, mapper,
                new ObjectMapper().findAndRegisterModules());
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/model-x", 200, "<html><body>body</body></html>",
                        "text/html", LocalDateTime.of(2026, 8, 26, 5, 0), List.of()));
        when(failingParser.parse(any(), any(), any()))
                .thenThrow(new AiNewsContentExtractionException("extractor unavailable"));

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.capture(7L, "https://openai.com/index/model-x"));

        assertEquals(502, rejected.getCode());
        assertEquals("extraction_error", inserted.get().getCaptureStatus());
        assertEquals(200, inserted.get().getHttpStatus());
        assertTrue(inserted.get().getCaptureError().contains("extractor unavailable"));
    }

    @Test
    void rejectsIncompleteTransportBodyBeforeExtractionInsteadOfPersistingTruncatedEvidence() throws Exception {
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/large", 200, "truncated html", "text/html",
                        LocalDateTime.of(2026, 8, 26, 5, 0), List.of(), false, null));

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.capture(7L, "https://openai.com/index/large"));

        assertEquals(413, rejected.getCode());
        assertEquals("body_too_large", inserted.get().getCaptureStatus());
        assertTrue(inserted.get().getCaptureError().contains("bytes"));
    }

    @Test
    void rejectsExtractedMainContentBelowConfiguredEvidenceFloor() throws Exception {
        AiNewsOfficialCaptureProperties properties = new AiNewsOfficialCaptureProperties();
        properties.setMinTextChars(200);
        properties.setAllowExtractionFallback(true);
        service = new AiNewsSourceCaptureService(fetcher, properties,
                new AiNewsSourceRegistry(),
                new AiNewsSourceDocumentParser(new ObjectMapper().findAndRegisterModules()),
                mapper, new ObjectMapper().findAndRegisterModules());
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://example.com/short", 200,
                        "<html><body><article>Only a short teaser is available.</article></body></html>",
                        "text/html", LocalDateTime.of(2026, 8, 26, 5, 0), List.of()));

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.capture(7L, "https://example.com/short"));

        assertEquals(409, rejected.getCode());
        assertEquals("insufficient_content", inserted.get().getCaptureStatus());
        assertTrue(inserted.get().getCaptureError().contains("低于 200"));
    }

    @Test
    void persistsProxyFallbackAsPartOfCaptureTransportProvenance() throws Exception {
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/model-x", 200,
                        "<html><body><article>OpenAI released Model X to developers worldwide.</article></body></html>",
                        "text/html", LocalDateTime.of(2026, 8, 26, 5, 0), List.of(),
                        true, null, "proxy_fallback"));

        AiNewsSourceCaptureService.CaptureSummary summary = service.capture(
                7L, "https://openai.com/index/model-x");

        assertEquals("READ_ONLY_HTTP_PROXY_FALLBACK", summary.captureMethod());
        assertEquals("READ_ONLY_HTTP_PROXY_FALLBACK", inserted.get().getCaptureMethod());
    }

    @Test
    void bindsApprovedStructuredSourceTimeWhenArticleMetadataHasNoExactTime() throws Exception {
        AiNewsSourceTimeAttestationService attestations =
                mock(AiNewsSourceTimeAttestationService.class);
        service = new AiNewsSourceCaptureService(fetcher, configuredProperties(),
                new AiNewsSourceRegistry(),
                new AiNewsSourceDocumentParser(new ObjectMapper().findAndRegisterModules()),
                mapper, new ObjectMapper().findAndRegisterModules(), attestations);
        String quote = "OpenAI expanded its presence for developers and businesses in Brazil.";
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/brazil/", 200,
                        "<html><body><article>" + quote + "</article></body></html>",
                        "text/html", LocalDateTime.of(2026, 8, 28, 5, 0), List.of()));
        String hash = "b".repeat(64);
        when(attestations.resolve("https://openai.com/index/brazil/"))
                .thenReturn(new AiNewsSourceTimeAttestationService.Resolution("BOUND",
                        new AiNewsSourceTimeAttestationService.Attestation(91L,
                                LocalDateTime.of(2026, 8, 27, 3, 0),
                                "Thu, 27 Aug 2026 03:00:00 GMT",
                                "STRUCTURED_FEED", hash)));

        AiNewsSourceCaptureService.CaptureSummary summary = service.capture(
                7L, "https://openai.com/index/brazil");

        assertEquals("2026-08-27T03:00:00Z", summary.sourcePublishedAtUtc());
        assertEquals("STRUCTURED_SOURCE", summary.sourceTimeOrigin());
        assertEquals("BOUND", summary.sourceTimeAttestationStatus());
        assertEquals(91L, summary.sourceTimeItemVersionId());
        assertEquals(hash, summary.sourceTimeAttestationHash());
        AiNewsSourceCaptureEntity persisted = inserted.get();
        when(mapper.selectById(501L)).thenReturn(persisted);
        when(attestations.validate(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(new AiNewsSourceTimeAttestationService.Validation(true, "VALID"));

        AiNewsSourceCaptureService.BoundCapture bound = service.bind(7L, 501L, quote,
                Instant.parse("2026-08-27T02:10:00Z"),
                Instant.parse("2026-08-28T02:10:00Z"));

        assertEquals(quote, bound.authoritativeQuote());
    }

    @Test
    void structuredTimeConflictKeepsBodyReadableButFailsLatestWindowClosed() throws Exception {
        AiNewsSourceTimeAttestationService attestations =
                mock(AiNewsSourceTimeAttestationService.class);
        service = new AiNewsSourceCaptureService(fetcher, configuredProperties(),
                new AiNewsSourceRegistry(),
                new AiNewsSourceDocumentParser(new ObjectMapper().findAndRegisterModules()),
                mapper, new ObjectMapper().findAndRegisterModules(), attestations);
        String quote = "OpenAI expanded its presence for developers and businesses in Brazil.";
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenReturn(new OfficialSourceHttpFetcher.FetchResult(
                        "https://openai.com/index/brazil", 200,
                        "<html><body><article>" + quote + "</article></body></html>",
                        "text/html", LocalDateTime.of(2026, 8, 28, 5, 0), List.of()));
        when(attestations.resolve(anyString()))
                .thenReturn(AiNewsSourceTimeAttestationService.Resolution.of("CONFLICT"));

        AiNewsSourceCaptureService.CaptureSummary summary = service.capture(
                7L, "https://openai.com/index/brazil");

        assertEquals(null, summary.sourcePublishedAtUtc());
        assertEquals("NONE", summary.sourceTimeOrigin());
        assertEquals("CONFLICT", summary.sourceTimeAttestationStatus());
        when(mapper.selectById(501L)).thenReturn(inserted.get());
        NewsClawException error = assertThrows(NewsClawException.class,
                () -> service.bind(7L, 501L, quote,
                        Instant.parse("2026-08-27T02:10:00Z"),
                        Instant.parse("2026-08-28T02:10:00Z")));
        assertTrue(error.getMessage().contains("缺少带时区"));
    }

    @Test
    void bindRejectsStructuredTimeWhoseAuditChainNoLongerValidates() {
        AiNewsSourceTimeAttestationService attestations =
                mock(AiNewsSourceTimeAttestationService.class);
        service = new AiNewsSourceCaptureService(fetcher, configuredProperties(),
                new AiNewsSourceRegistry(),
                new AiNewsSourceDocumentParser(new ObjectMapper().findAndRegisterModules()),
                mapper, new ObjectMapper().findAndRegisterModules(), attestations);
        AiNewsSourceCaptureEntity row = successfulCapture();
        row.setSourceTimeOrigin("STRUCTURED_SOURCE");
        row.setSourceTimeAttestationStatus("BOUND");
        row.setSourceTimeItemVersionId(91L);
        row.setSourceTimeAttestationHash("b".repeat(64));
        row.setPublishedAtRaw("2026-08-26T04:30:00Z");
        when(mapper.selectById(501L)).thenReturn(row);
        when(attestations.validate(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(new AiNewsSourceTimeAttestationService.Validation(false, "SUPERSEDED"));

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> service.bind(7L, 501L,
                        "OpenAI released Model X to developers worldwide.",
                        Instant.parse("2026-08-26T03:00:00Z"),
                        Instant.parse("2026-08-26T05:00:00Z")));

        assertTrue(error.getMessage().contains("SUPERSEDED"));
    }

    private static AiNewsOfficialCaptureProperties configuredProperties() {
        AiNewsOfficialCaptureProperties properties = new AiNewsOfficialCaptureProperties();
        properties.setMinTextChars(1);
        properties.setAllowExtractionFallback(true);
        return properties;
    }

    private static AiNewsSourceCaptureEntity successfulCapture() {
        AiNewsSourceCaptureEntity row = new AiNewsSourceCaptureEntity();
        row.setId(501L);
        row.setWorkspaceId(7L);
        row.setSourceUrl("https://openai.com/news/model-x");
        row.setFinalUrl("https://openai.com/index/model-x");
        row.setSourceTitle("Model X");
        row.setSourcePublishedAt(LocalDateTime.of(2026, 8, 26, 4, 30));
        row.setPublishedAtRaw("2026-08-26T04:30:00Z");
        row.setPublishedAtMethod("HTML_META");
        row.setSourceTimeOrigin("PAGE_METADATA");
        row.setSourceTimeAttestationStatus("NOT_REQUIRED");
        row.setSourceTier("official");
        row.setHttpStatus(200);
        row.setFetchedAt(LocalDateTime.of(2026, 8, 26, 5, 0));
        row.setContentHash("a".repeat(64));
        row.setCaptureMethod("READ_ONLY_HTTP");
        row.setRedirectChainJson("[]");
        row.setExtractedText("Model X OpenAI released Model X to developers worldwide.");
        row.setExtractedTextHash("d742a9f7e12a9cb1900ce3e819e372260eb53390eac4778862f197bf27410349");
        row.setTextLength(row.getExtractedText().length());
        row.setExtractorName("jsoup_document_text");
        row.setExtractorVersion("1");
        row.setExtractorConfigHash(
                "b5b24093503f71176939d0ca019d389bb065467a56f75888dbf03ea1cec718e0");
        row.setExtractionFallback(1);
        row.setCaptureStatus("success");
        row.setDeleted(0);
        return row;
    }
}

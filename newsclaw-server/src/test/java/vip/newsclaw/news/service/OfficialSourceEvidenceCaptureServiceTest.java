package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsCaptureStatus;
import vip.newsclaw.news.model.AiNewsEvidenceCaptureTrace;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEvidenceRequest;
import vip.newsclaw.news.model.AiNewsEventEntity;

import java.time.LocalDateTime;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficialSourceEvidenceCaptureServiceTest {

    private OfficialSourceHttpFetcher fetcher;
    private AiNewsOfficialCaptureProperties properties;
    private AiNewsEventService eventService;
    private AiNewsCaptureAttemptService captureAttemptService;
    private OfficialSourceEvidenceCaptureService service;

    @BeforeEach
    void setUp() {
        fetcher = mock(OfficialSourceHttpFetcher.class);
        properties = new AiNewsOfficialCaptureProperties();
        eventService = mock(AiNewsEventService.class);
        captureAttemptService = mock(AiNewsCaptureAttemptService.class);
        service = new OfficialSourceEvidenceCaptureService(fetcher, properties, eventService,
                new ObjectMapper(), new AiNewsSourceRegistry(), captureAttemptService);
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(101L);
        event.setWorkspaceId(7L);
        when(eventService.findEvent(7L, 101L)).thenReturn(event);
    }

    @Test
    @DisplayName("只读官方抓取归档引用链，但不自动调用事件核验")
    void capturesOfficialSourceWithoutAutoVerification() throws Exception {
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class))).thenReturn(
                new OfficialSourceHttpFetcher.FetchResult("https://openai.com/index/new-model", 200,
                        "<html><head><title>OpenAI &amp; Model</title><script>secret()</script></head>"
                                + "<body><h1>Model announcement</h1><p>Official details for the release.</p></body></html>",
                        "text/html; charset=utf-8", LocalDateTime.of(2026, 8, 24, 8, 0),
                        List.of("https://openai.com/news/new-model")));
        AiNewsEvidenceEntity evidence = new AiNewsEvidenceEntity();
        evidence.setId(201L);
        when(eventService.attachCapturedOfficialEvidence(eq(7L), eq(101L), any(), any())).thenReturn(evidence);

        AiNewsEvidenceEntity result = service.capture(7L, 101L, "https://openai.com/news/new-model",
                "OpenAI announced a new model.");

        assertEquals(201L, result.getId());
        ArgumentCaptor<AiNewsEvidenceRequest> request = ArgumentCaptor.forClass(AiNewsEvidenceRequest.class);
        ArgumentCaptor<AiNewsEvidenceCaptureTrace> trace = ArgumentCaptor.forClass(AiNewsEvidenceCaptureTrace.class);
        verify(eventService).attachCapturedOfficialEvidence(eq(7L), eq(101L), request.capture(), trace.capture());
        assertEquals("official", request.getValue().sourceTier());
        assertEquals("OpenAI & Model", request.getValue().sourceTitle());
        assertTrue(request.getValue().quote().contains("Official details"));
        assertTrue(!request.getValue().quote().contains("secret"));
        assertEquals("READ_ONLY_HTTP", trace.getValue().captureMethod());
        assertEquals(64, trace.getValue().contentHash().length());
        verify(captureAttemptService).record(eq(7L), eq(101L),
                eq("https://openai.com/news/new-model"), eq("https://openai.com/index/new-model"),
                eq(AiNewsCaptureStatus.SUCCESS), eq(null), eq(200), any());
        verify(eventService, never()).verify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("403 被记为 blocked，且不会被解释成官方未发布")
    void recordsBlockedCapture() throws Exception {
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class))).thenReturn(
                new OfficialSourceHttpFetcher.FetchResult("https://openai.com/index/private", 403,
                        "Access denied", "text/plain", LocalDateTime.now(), List.of()));

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> service.capture(7L, 101L, "https://openai.com/index/private", "claim"));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("不能据此判断官方未发布"));
        verify(captureAttemptService).record(eq(7L), eq(101L), any(), any(),
                eq(AiNewsCaptureStatus.BLOCKED), any(), eq(403), any());
        verify(eventService, never()).attachCapturedOfficialEvidence(any(), any(), any(), any());
    }

    @Test
    @DisplayName("网络超时有独立 timeout 语义")
    void recordsTimeoutCapture() throws Exception {
        when(fetcher.fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class)))
                .thenThrow(new HttpTimeoutException("request timed out"));

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> service.capture(7L, 101L, "https://openai.com/index/slow", "claim"));

        assertEquals(504, error.getCode());
        verify(captureAttemptService).record(eq(7L), eq(101L), any(), eq(null),
                eq(AiNewsCaptureStatus.TIMEOUT), any(), eq(null), any());
    }

    @Test
    @DisplayName("非官方域名在网络请求前被拒绝")
    void rejectsUnregisteredHostBeforeFetch() throws Exception {
        assertThrows(NewsClawException.class, () -> service.capture(7L, 101L,
                "https://example.invalid/article", "untrusted"));

        verify(fetcher, never()).fetch(any(), any(Integer.class), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("部署开关关闭时拒绝官方来源抓取")
    void honorsDisabledFlag() {
        properties.setEnabled(false);

        assertThrows(NewsClawException.class, () -> service.capture(7L, 101L,
                "https://openai.com/index/test", "test"));
    }
}

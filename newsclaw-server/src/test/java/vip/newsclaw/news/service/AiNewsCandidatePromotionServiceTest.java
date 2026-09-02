package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsCandidateEntity;
import vip.newsclaw.news.model.AiNewsCandidatePromotionRequest;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEventUpsertRequest;
import vip.newsclaw.news.model.AiNewsScanRunEntity;
import vip.newsclaw.news.model.AiNewsSourceCaptureEntity;
import vip.newsclaw.news.repository.AiNewsCandidateMapper;
import vip.newsclaw.news.repository.AiNewsScanRunMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiNewsCandidatePromotionServiceTest {

    @Mock
    private AiNewsCandidateMapper candidateMapper;
    @Mock
    private AiNewsScanRunMapper scanMapper;
    @Mock
    private AiNewsEventService eventService;
    @Mock
    private AiNewsSourceCaptureService captureService;

    private AiNewsCandidatePromotionService service;

    @BeforeEach
    void setUp() {
        service = new AiNewsCandidatePromotionService(candidateMapper, scanMapper, eventService);
        service.setSourceCaptureService(captureService);
    }

    @Test
    void promotesOnlyAfterAcceptanceAndBindsTheCandidateCapture() {
        AiNewsCandidateEntity candidate = candidate(42L, 7L, 99L, 500L);
        AiNewsScanRunEntity run = run(99L, 7L);
        AiNewsSourceCaptureEntity capture = capture(500L, 7L,
                "https://example.com/story");
        when(candidateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(candidate);
        when(candidateMapper.selectForUpdate(42L, 7L)).thenReturn(candidate);
        when(scanMapper.selectForUpdate(99L, 7L)).thenReturn(run);
        when(eventService.findEventByKey(eq(7L), anyString())).thenReturn(null);
        when(captureService.bind(eq(7L), eq(500L), anyString(), any(), any()))
                .thenReturn(new AiNewsSourceCaptureService.BoundCapture(capture,
                        "The provider released model X.", 0, 31, "NORMALIZED_EXACT"));
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(700L);
        when(eventService.upsertCaptured(eq(7L), any(AiNewsEventUpsertRequest.class), any(), any()))
                .thenReturn(event);
        when(candidateMapper.linkPromotedEvent(eq(42L), eq(7L), eq(700L), eq(500L), any()))
                .thenReturn(1);

        AiNewsEventEntity promoted = service.promote(7L, 42L,
                new AiNewsCandidatePromotionRequest(
                        "The provider released model X.",
                        "The provider released model X.",
                        "model", List.of("Provider"), "entails", 1.0D));

        assertEquals(700L, promoted.getId());
        ArgumentCaptor<AiNewsEventUpsertRequest> request =
                ArgumentCaptor.forClass(AiNewsEventUpsertRequest.class);
        verify(eventService).upsertCaptured(eq(7L), request.capture(), any(), any());
        assertEquals("candidate-fact:" + AiNewsAtomicFactGuard.prepare(
                "model", List.of("Provider"), "The provider released model X.",
                Instant.parse("2026-08-29T00:00:00Z")).eventKeyMaterial(),
                request.getValue().eventKey());
        assertEquals(500L, request.getValue().evidence().getFirst().captureId());
        assertEquals("The provider released model X.", request.getValue().evidence().getFirst().quote());
        verify(candidateMapper).linkPromotedEvent(eq(42L), eq(7L), eq(700L), eq(500L), any());
    }

    @Test
    void refusesUnacceptedCandidateBeforeCallingCaptureOrEventServices() {
        AiNewsCandidateEntity candidate = candidate(42L, 7L, 99L, 500L);
        candidate.setReviewStatus("PENDING");
        when(candidateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(candidate);
        when(scanMapper.selectForUpdate(99L, 7L)).thenReturn(run(99L, 7L));
        when(candidateMapper.selectForUpdate(42L, 7L)).thenReturn(candidate);

        assertThrows(NewsClawException.class, () -> service.promote(7L, 42L,
                new AiNewsCandidatePromotionRequest("A complete atomic claim", "a quote",
                        "model", List.of(), "entails", 1.0D)));

        verify(captureService, never()).bind(any(), any(), anyString(), any(), any());
        verify(eventService, never()).upsertCaptured(any(), any(), any(), any());
    }

    @Test
    void sameAtomicFactAttachesToExistingEventWithoutReopeningIt() {
        AiNewsCandidateEntity candidate = candidate(43L, 7L, 99L, 501L);
        AiNewsScanRunEntity run = run(99L, 7L);
        AiNewsSourceCaptureEntity capture = capture(501L, 7L, "https://example.com/story");
        AiNewsEventEntity existing = new AiNewsEventEntity();
        existing.setId(701L);
        existing.setStatus("researching");
        when(candidateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(candidate);
        when(candidateMapper.selectForUpdate(43L, 7L)).thenReturn(candidate);
        when(scanMapper.selectForUpdate(99L, 7L)).thenReturn(run);
        when(captureService.bind(eq(7L), eq(501L), anyString(), any(), any()))
                .thenReturn(new AiNewsSourceCaptureService.BoundCapture(capture,
                        "The provider released model X.", 0, 31, "NORMALIZED_EXACT"));
        when(eventService.findEventByKey(eq(7L), anyString())).thenReturn(existing);
        when(candidateMapper.linkPromotedEvent(eq(43L), eq(7L), eq(701L), eq(501L), any()))
                .thenReturn(1);

        assertEquals(701L, service.promote(7L, 43L,
                new AiNewsCandidatePromotionRequest("The provider released model X.",
                        "The provider released model X.", "model", List.of("Provider"),
                        "entails", 1.0D)).getId());
        verify(eventService, never()).upsertCaptured(any(), any(), any(), any());
    }

    @Test
    void refusesPromotionFromFailedRunBeforeBindingCapture() {
        AiNewsCandidateEntity candidate = candidate(44L, 7L, 100L, 502L);
        AiNewsScanRunEntity failed = run(100L, 7L);
        failed.setRunStatus("FAILED");
        when(candidateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(candidate);
        when(candidateMapper.selectForUpdate(44L, 7L)).thenReturn(candidate);
        when(scanMapper.selectForUpdate(100L, 7L)).thenReturn(failed);

        assertThrows(NewsClawException.class, () -> service.promote(7L, 44L,
                new AiNewsCandidatePromotionRequest("The provider released model X.",
                        "The provider released model X.", "model", List.of("Provider"),
                        "entails", 1.0D)));

        verify(captureService, never()).bind(any(), any(), anyString(), any(), any());
        verify(eventService, never()).findEventByKey(any(), anyString());
        verify(eventService, never()).upsertCaptured(any(), any(), any(), any());
    }

    private static AiNewsCandidateEntity candidate(long id, long workspace, long runId, long captureId) {
        AiNewsCandidateEntity row = new AiNewsCandidateEntity();
        row.setId(id);
        row.setWorkspaceId(workspace);
        row.setScanRunId(runId);
        row.setCanonicalUrl("https://www.example.com/story?utm_source=feed");
        row.setSelectionStatus("SELECTED");
        row.setReviewStatus("ACCEPTED");
        row.setReviewedBy("editor@example.com");
        row.setReviewedAt(LocalDateTime.of(2026, 8, 29, 1, 1));
        row.setReviewOrigin("HUMAN_WEB");
        row.setCaptureStatus("SUCCESS");
        row.setCaptureId(captureId);
        row.setLastSeenAt(LocalDateTime.of(2026, 8, 29, 1, 0));
        row.setDeleted(0);
        return row;
    }

    private static AiNewsScanRunEntity run(long id, long workspace) {
        AiNewsScanRunEntity row = new AiNewsScanRunEntity();
        row.setId(id);
        row.setWorkspaceId(workspace);
        row.setRunStatus("COMPLETED");
        row.setWindowStart(LocalDateTime.of(2026, 8, 29, 0, 0));
        row.setWindowEnd(LocalDateTime.of(2026, 8, 30, 0, 0));
        row.setStartedAt(LocalDateTime.of(2026, 8, 29, 0, 0));
        row.setDeleted(0);
        return row;
    }

    private static AiNewsSourceCaptureEntity capture(long id, long workspace, String finalUrl) {
        AiNewsSourceCaptureEntity row = new AiNewsSourceCaptureEntity();
        row.setId(id);
        row.setWorkspaceId(workspace);
        row.setFinalUrl(finalUrl);
        row.setSourcePublishedAt(LocalDateTime.of(2026, 8, 29, 2, 0));
        return row;
    }
}

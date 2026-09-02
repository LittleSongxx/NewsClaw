package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.newsclaw.news.model.AiNewsCandidateEntity;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsCandidateCaptureWorkerTest {

    @Test
    void capturesMultipleCandidatesFromOneDomainWithoutArtificiallyDroppingQueueItems() {
        AiNewsCandidatePipelineProperties properties = new AiNewsCandidatePipelineProperties();
        properties.setCaptureEnabled(true);
        AiNewsCandidatePipelineService pipeline = mock(AiNewsCandidatePipelineService.class);
        AiNewsSourceCaptureService captures = mock(AiNewsSourceCaptureService.class);
        var worker = new AiNewsCandidateCaptureWorker(pipeline, captures, properties);
        when(pipeline.captureQueue(50L, 10)).thenReturn(List.of(
                candidate(1L, "https://example.com/one"),
                candidate(2L, "https://example.com/two"),
                candidate(3L, "https://other.example/three")));
        when(pipeline.claimCaptureLease(anyLong())).thenAnswer(invocation ->
                new AiNewsCandidatePipelineService.CaptureLease(invocation.getArgument(0), 1));
        when(pipeline.captureSucceeded(anyLong(), anyLong(), anyInt())).thenReturn(true);
        AiNewsSourceCaptureService.CaptureSummary capture =
                mock(AiNewsSourceCaptureService.CaptureSummary.class);
        when(capture.captureId()).thenReturn("900");
        when(captures.capture(anyLong(), anyString())).thenReturn(capture);

        var result = worker.run(50L, 2);

        assertEquals(2, result.claimed());
        assertEquals(2, result.succeeded());
        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(captures, times(2)).capture(eq(7L), urls.capture());
        assertEquals(List.of("https://example.com/one", "https://example.com/two"),
                urls.getAllValues());
        verify(pipeline).captureSucceeded(1L, 900L, 1);
        verify(pipeline).captureSucceeded(2L, 900L, 1);
        verify(pipeline).recoverStaleCaptures(isNull(), eq(50L), any(Duration.class));
    }

    @Test
    void fetchedPageWithoutWindowTimeIsNotMarkedAsUsableCapture() {
        AiNewsCandidatePipelineProperties properties = new AiNewsCandidatePipelineProperties();
        properties.setCaptureEnabled(true);
        AiNewsCandidatePipelineService pipeline = mock(AiNewsCandidatePipelineService.class);
        AiNewsSourceCaptureService captures = mock(AiNewsSourceCaptureService.class);
        var worker = new AiNewsCandidateCaptureWorker(pipeline, captures, properties);
        AiNewsCandidateEntity candidate = candidate(4L, "https://example.com/undated");
        when(pipeline.captureQueue(51L, 5)).thenReturn(List.of(candidate));
        when(pipeline.claimCaptureLease(4L))
                .thenReturn(new AiNewsCandidatePipelineService.CaptureLease(4L, 1));
        AiNewsSourceCaptureService.CaptureSummary capture =
                mock(AiNewsSourceCaptureService.CaptureSummary.class);
        when(capture.captureId()).thenReturn("901");
        when(captures.capture(7L, candidate.getCanonicalUrl())).thenReturn(capture);
        when(pipeline.captureWindowFailure(4L, null))
                .thenReturn("SOURCE_PUBLISHED_AT_MISSING");
        when(pipeline.captureFailed(eq(4L), eq("SOURCE_PUBLISHED_AT_MISSING"), eq(false),
                anyInt(), eq(Duration.ZERO), eq(1))).thenReturn(true);

        var result = worker.run(51L, 1);

        assertEquals(1, result.failed());
        assertEquals(0, result.succeeded());
        verify(pipeline, never()).captureSucceeded(anyLong(), anyLong(), anyInt());
    }

    private static AiNewsCandidateEntity candidate(Long id, String url) {
        AiNewsCandidateEntity candidate = new AiNewsCandidateEntity();
        candidate.setId(id);
        candidate.setWorkspaceId(7L);
        candidate.setCanonicalUrl(url);
        return candidate;
    }
}

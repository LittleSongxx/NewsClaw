package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import vip.newsclaw.news.model.AiNewsCandidateEntity;
import vip.newsclaw.news.model.AiNewsScanRunEntity;
import vip.newsclaw.news.repository.AiNewsCandidateMapper;
import vip.newsclaw.news.repository.AiNewsCandidateObservationMapper;
import vip.newsclaw.news.repository.AiNewsScanRunMapper;

import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsCandidatePipelineServiceTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiNewsScanRunEntity.class);
    }

    @Test
    void invalidWindowIsAClientError() {
        AiNewsCandidatePipelineService service = new AiNewsCandidatePipelineService(
                mock(AiNewsScanRunMapper.class), mock(AiNewsCandidateMapper.class),
                mock(AiNewsCandidateObservationMapper.class), mock(AiNewsSourceRegistry.class),
                new ObjectMapper());
        Instant now = Instant.now();

        vip.newsclaw.exception.NewsClawException error = assertThrows(
                vip.newsclaw.exception.NewsClawException.class,
                () -> service.startScan(1L, "manual", "AI", now, now, "v1"));

        assertEquals(400, error.getCode());
    }

    @Test
    void disabledCaptureCompletesCandidateOnlyRunWithoutInventingFailure() {
        AiNewsScanRunMapper scans = mock(AiNewsScanRunMapper.class);
        AiNewsCandidateMapper candidates = mock(AiNewsCandidateMapper.class);
        AiNewsCandidateObservationMapper observations = mock(AiNewsCandidateObservationMapper.class);
        AiNewsCandidatePipelineProperties properties = new AiNewsCandidatePipelineProperties();
        properties.setCaptureEnabled(false);
        AiNewsScanRunEntity run = new AiNewsScanRunEntity();
        run.setId(7L);
        run.setWorkspaceId(1L);
        run.setRunStatus("CANDIDATES_PERSISTED");
        run.setDeleted(0);
        AiNewsCandidateEntity candidate = new AiNewsCandidateEntity();
        candidate.setId(11L);
        candidate.setCaptureStatus("PENDING");
        candidate.setReviewStatus("PENDING");

        when(scans.selectById(7L)).thenReturn(run);
        when(scans.selectForUpdate(7L, 1L)).thenReturn(run);
        when(observations.selectSelectedCandidateIds(7L)).thenReturn(List.of(11L));
        when(observations.selectCandidateIds(7L)).thenReturn(List.of(11L));
        when(candidates.selectBatchIds(List.of(11L))).thenReturn(List.of(candidate));
        when(scans.update(any(), any())).thenReturn(1);

        AiNewsCandidatePipelineService service = new AiNewsCandidatePipelineService(
                scans, candidates, observations, mock(AiNewsSourceRegistry.class),
                new ObjectMapper(), properties);

        service.completeScan(7L);

        assertEquals("COMPLETED", run.getRunStatus());
        assertEquals(0, run.getCaptureSuccessCount());
        assertEquals(0, run.getCaptureFailureCount());
        verify(scans).update(any(), any());
    }

    @Test
    void captureCompletionLocksOwningRunBeforeCandidate() {
        AiNewsScanRunMapper scans = mock(AiNewsScanRunMapper.class);
        AiNewsCandidateMapper candidates = mock(AiNewsCandidateMapper.class);
        AiNewsCandidateObservationMapper observations = mock(AiNewsCandidateObservationMapper.class);
        AiNewsCandidateEntity candidate = new AiNewsCandidateEntity();
        candidate.setId(11L);
        candidate.setWorkspaceId(1L);
        candidate.setScanRunId(7L);
        candidate.setDeleted(0);
        AiNewsScanRunEntity run = new AiNewsScanRunEntity();
        run.setId(7L);
        run.setWorkspaceId(1L);
        run.setDeleted(0);
        when(candidates.selectById(11L)).thenReturn(candidate);
        when(scans.selectForUpdate(7L, 1L)).thenReturn(run);
        when(candidates.selectForUpdate(11L, 1L)).thenReturn(candidate);
        when(candidates.completeCaptureSucceeded(anyLong(), anyInt(), anyLong(), any()))
                .thenReturn(0);

        AiNewsCandidatePipelineService service = new AiNewsCandidatePipelineService(
                scans, candidates, observations, mock(AiNewsSourceRegistry.class),
                new ObjectMapper());

        assertFalse(service.captureSucceeded(11L, 900L, 1));
        InOrder order = inOrder(scans, candidates);
        order.verify(scans).selectForUpdate(7L, 1L);
        order.verify(candidates).selectForUpdate(11L, 1L);
        order.verify(candidates).completeCaptureSucceeded(anyLong(), anyInt(), anyLong(), any());
    }
}

package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsIngestionRunEntity;
import vip.newsclaw.news.model.AiNewsRawCaptureMetadataRow;
import vip.newsclaw.news.model.AiNewsRunItemObservationRow;
import vip.newsclaw.news.model.AiNewsSourceEndpointEntity;
import vip.newsclaw.news.repository.AiNewsIngestionRunItemMapper;
import vip.newsclaw.news.repository.AiNewsIngestionRunMapper;
import vip.newsclaw.news.repository.AiNewsRawCaptureMapper;
import vip.newsclaw.news.repository.AiNewsSourceEndpointMapper;

import java.util.List;

/** Bounded, read-only operator view over the global structured-ingestion ledger. */
@Service
public class AiNewsIngestionAdminService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_RUN_OBSERVATIONS = 500;

    private final AiNewsSourceEndpointMapper endpointMapper;
    private final AiNewsIngestionRunMapper runMapper;
    private final AiNewsIngestionRunItemMapper runItemMapper;
    private final AiNewsRawCaptureMapper rawCaptureMapper;

    public AiNewsIngestionAdminService(AiNewsSourceEndpointMapper endpointMapper,
                                       AiNewsIngestionRunMapper runMapper,
                                       AiNewsIngestionRunItemMapper runItemMapper,
                                       AiNewsRawCaptureMapper rawCaptureMapper) {
        this.endpointMapper = endpointMapper;
        this.runMapper = runMapper;
        this.runItemMapper = runItemMapper;
        this.rawCaptureMapper = rawCaptureMapper;
    }

    public IPage<AiNewsSourceEndpointEntity> endpoints(int page, int size,
                                                        String providerId, Boolean enabled) {
        LambdaQueryWrapper<AiNewsSourceEndpointEntity> query =
                new LambdaQueryWrapper<AiNewsSourceEndpointEntity>()
                        .eq(AiNewsSourceEndpointEntity::getDeleted, 0)
                        .eq(providerId != null && !providerId.isBlank(),
                                AiNewsSourceEndpointEntity::getProviderId,
                                providerId == null ? null : providerId.trim())
                        .eq(enabled != null, AiNewsSourceEndpointEntity::getEnabled, enabled)
                        .orderByAsc(AiNewsSourceEndpointEntity::getNextPollAt)
                        .orderByAsc(AiNewsSourceEndpointEntity::getEndpointKey);
        return endpointMapper.selectPage(page(page, size), query);
    }

    public IPage<AiNewsIngestionRunEntity> runs(int page, int size,
                                                Long endpointId, String status) {
        LambdaQueryWrapper<AiNewsIngestionRunEntity> query =
                new LambdaQueryWrapper<AiNewsIngestionRunEntity>()
                        .eq(AiNewsIngestionRunEntity::getDeleted, 0)
                        .eq(endpointId != null, AiNewsIngestionRunEntity::getEndpointId, endpointId)
                        .eq(status != null && !status.isBlank(),
                                AiNewsIngestionRunEntity::getRunStatus,
                                status == null ? null : status.trim().toLowerCase(java.util.Locale.ROOT))
                        .orderByDesc(AiNewsIngestionRunEntity::getStartedAt)
                        .orderByDesc(AiNewsIngestionRunEntity::getId);
        return runMapper.selectPage(page(page, size), query);
    }

    public RunInspection inspectRun(Long runId) {
        if (runId == null) throw new NewsClawException(400, "ingestion run id is required");
        AiNewsIngestionRunEntity run = runMapper.selectById(runId);
        if (run == null || Integer.valueOf(1).equals(run.getDeleted())) {
            throw new NewsClawException(404, "ingestion run not found");
        }
        List<AiNewsRunItemObservationRow> items = runItemMapper.selectRunObservations(
                runId, MAX_RUN_OBSERVATIONS);
        List<AiNewsRawCaptureMetadataRow> captures = rawCaptureMapper.selectRunMetadata(
                runId, MAX_RUN_OBSERVATIONS);
        return new RunInspection(run, items, captures);
    }

    private static <T> Page<T> page(int requestedPage, int requestedSize) {
        return new Page<>(Math.max(1, requestedPage),
                Math.min(Math.max(1, requestedSize), MAX_PAGE_SIZE));
    }

    public record RunInspection(AiNewsIngestionRunEntity run,
                                List<AiNewsRunItemObservationRow> items,
                                List<AiNewsRawCaptureMetadataRow> captures) {
        public RunInspection {
            items = items == null ? List.of() : List.copyOf(items);
            captures = captures == null ? List.of() : List.copyOf(captures);
        }
    }
}

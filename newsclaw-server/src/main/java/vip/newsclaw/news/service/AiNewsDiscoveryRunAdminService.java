package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.evaluation.AiNewsDiscoveryStabilityEvaluator;
import vip.newsclaw.news.model.AiNewsDiscoveryRunEntity;
import vip.newsclaw.news.repository.AiNewsDiscoveryRunMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.time.Instant;

/** Bounded, admin-only read view over discovery snapshots. */
@Service
public class AiNewsDiscoveryRunAdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AiNewsDiscoveryRunMapper mapper;
    private final ObjectMapper objectMapper;
    private final AiNewsDiscoveryStabilityEvaluator stabilityEvaluator;
    private final AiNewsDiscoverySearchService discoverySearchService;

    public AiNewsDiscoveryRunAdminService(AiNewsDiscoveryRunMapper mapper,
                                          ObjectMapper objectMapper,
                                          AiNewsDiscoveryStabilityEvaluator stabilityEvaluator,
                                          AiNewsDiscoverySearchService discoverySearchService) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.stabilityEvaluator = stabilityEvaluator;
        this.discoverySearchService = discoverySearchService;
    }

    public IPage<AiNewsDiscoveryRunEntity> runs(int page,
                                                int size,
                                                Long workspaceId,
                                                String rankingPolicyVersion) {
        LambdaQueryWrapper<AiNewsDiscoveryRunEntity> query =
                new LambdaQueryWrapper<AiNewsDiscoveryRunEntity>()
                        .eq(AiNewsDiscoveryRunEntity::getDeleted, 0)
                        .eq(workspaceId != null,
                                AiNewsDiscoveryRunEntity::getWorkspaceId, workspaceId)
                        .eq(rankingPolicyVersion != null && !rankingPolicyVersion.isBlank(),
                                AiNewsDiscoveryRunEntity::getRankingPolicyVersion,
                                rankingPolicyVersion == null ? null : rankingPolicyVersion.trim())
                        .orderByDesc(AiNewsDiscoveryRunEntity::getObservedAt)
                        .orderByDesc(AiNewsDiscoveryRunEntity::getId);
        return mapper.selectPage(new Page<>(Math.max(1, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE)), query);
    }

    public RunInspection inspect(Long runId) {
        if (runId == null) throw new NewsClawException(400, "discovery run id is required");
        AiNewsDiscoveryRunEntity run = mapper.selectById(runId);
        if (run == null || Integer.valueOf(1).equals(run.getDeleted())) {
            throw new NewsClawException(404, "discovery run not found");
        }
        String snapshotJson = mapper.selectSnapshotJson(runId);
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new NewsClawException(500, "discovery run snapshot is missing");
        }
        try {
            return new RunInspection(run, objectMapper.readTree(snapshotJson));
        } catch (Exception e) {
            throw new NewsClawException(500, "discovery run snapshot is unreadable");
        }
    }

    public AiNewsDiscoveryStabilityEvaluator.StabilityReport stability(List<Long> runIds) {
        if (runIds == null || runIds.size() < 2 || runIds.size() > 20) {
            throw new NewsClawException(400, "stability requires 2 to 20 discovery run ids");
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(runIds));
        if (distinctIds.size() != runIds.size() || distinctIds.stream().anyMatch(id -> id == null)) {
            throw new NewsClawException(400, "stability run ids must be non-null and distinct");
        }
        List<AiNewsDiscoverySearchService.DiscoveryBatch> batches = new ArrayList<>();
        Set<Long> workspaceIds = new LinkedHashSet<>();
        for (Long runId : distinctIds) {
            RunInspection inspection = inspect(runId);
            workspaceIds.add(inspection.run().getWorkspaceId());
            try {
                AiNewsDiscoverySearchService.DiscoveryBatch batch = objectMapper.treeToValue(
                        inspection.snapshot(), AiNewsDiscoverySearchService.DiscoveryBatch.class);
                batches.add(batch.withPersistence(runId, true));
            } catch (Exception e) {
                throw new NewsClawException(500, "discovery run snapshot contract is unreadable");
            }
        }
        if (workspaceIds.size() != 1) {
            throw new NewsClawException(400, "stability runs must belong to the same workspace");
        }
        try {
            return stabilityEvaluator.evaluate(batches);
        } catch (IllegalArgumentException e) {
            throw new NewsClawException(400, e.getMessage());
        }
    }

    public AiNewsDiscoverySearchService.DiscoveryBatch replay(Long runId, Integer maxCandidates) {
        RunInspection inspection = inspect(runId);
        try {
            AiNewsDiscoverySearchService.DiscoveryBatch frozen = objectMapper.treeToValue(
                    inspection.snapshot(), AiNewsDiscoverySearchService.DiscoveryBatch.class);
            return discoverySearchService.replay(inspection.run().getTopic(), frozen, maxCandidates);
        } catch (IllegalArgumentException e) {
            throw new NewsClawException(400, e.getMessage());
        } catch (Exception e) {
            throw new NewsClawException(500, "discovery run snapshot contract is unreadable");
        }
    }

    public AiNewsDiscoverySearchService.DiscoveryBatch discover(Long workspaceId,
                                                                String topic,
                                                                String windowStart,
                                                                String windowEnd,
                                                                Integer maxCandidates) {
        if (windowStart == null || windowStart.isBlank()
                || windowEnd == null || windowEnd.isBlank()) {
            throw new NewsClawException(400, "windowStart and windowEnd are required");
        }
        try {
            return discoverySearchService.discover(workspaceId, topic,
                    Instant.parse(windowStart), Instant.parse(windowEnd), maxCandidates);
        } catch (java.time.format.DateTimeParseException e) {
            throw new NewsClawException(400, "windowStart/windowEnd must be ISO-8601 instants");
        }
    }

    public record RunInspection(AiNewsDiscoveryRunEntity run, JsonNode snapshot) {
    }
}

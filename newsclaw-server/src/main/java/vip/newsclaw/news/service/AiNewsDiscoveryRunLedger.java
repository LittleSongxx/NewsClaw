package vip.newsclaw.news.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.news.model.AiNewsDiscoveryRunEntity;
import vip.newsclaw.news.repository.AiNewsDiscoveryRunMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Durable write boundary for replayable discovery observations. */
@Service
public class AiNewsDiscoveryRunLedger {

    private final AiNewsDiscoveryRunMapper mapper;
    private final ObjectMapper objectMapper;

    public AiNewsDiscoveryRunLedger(AiNewsDiscoveryRunMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long persist(Long workspaceId,
                        String topic,
                        int requestedMaxCandidates,
                        AiNewsDiscoverySearchService.DiscoveryBatch batch) {
        if (batch == null) throw new IllegalArgumentException("discovery batch is required");
        AiNewsDiscoveryRunEntity entity = new AiNewsDiscoveryRunEntity();
        entity.setWorkspaceId(workspaceId == null ? 1L : workspaceId);
        entity.setTopic(topic == null ? "" : topic);
        entity.setWindowStart(utc(batch.windowStart()));
        entity.setWindowEnd(utc(batch.windowEnd()));
        entity.setObservedAt(utc(batch.observedAt()));
        entity.setRequestedMaxCandidates(requestedMaxCandidates);
        entity.setQueryCount(batch.queryCount());
        entity.setCachedQueryCount((int) batch.executions().stream()
                .filter(AiNewsDiscoverySearchService.QueryExecution::fromCache).count());
        entity.setSuccessfulQueryCount((int) batch.executions().stream()
                .filter(item -> item.failureMessage() == null || item.failureMessage().isBlank()).count());
        entity.setUniqueUrlCount(batch.uniqueUrlCount());
        entity.setSelectedCandidateCount(batch.candidates().size());
        entity.setStructuredSourceCount(batch.structuredSourceCount());
        entity.setRankingPolicyVersion(batch.rankingPolicyVersion());
        entity.setSnapshotHash(batch.snapshotHash());
        entity.setRankingHash(batch.rankingHash());
        entity.setSnapshotJson(json(batch));
        entity.setCreateTime(LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        entity.setDeleted(0);
        if (mapper.insert(entity) != 1 || entity.getId() == null) {
            throw new IllegalStateException("discovery run snapshot insert did not produce an id");
        }
        return entity.getId();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize discovery run snapshot", e);
        }
    }

    private static LocalDateTime utc(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("discovery snapshot timestamp is required");
        }
        return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
    }
}

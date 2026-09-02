package vip.newsclaw.memory.fact.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.newsclaw.memory.fact.model.FactContradictionEntity;
import vip.newsclaw.memory.fact.model.FactEntity;
import vip.newsclaw.memory.fact.repository.FactMapper;
import vip.newsclaw.memory.identity.MemoryScope;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Query service for the fact projection.
 * Read-only + bumpUseCount (the only accumulated column writer).
 *
 * @author NewsClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactQueryService {

    private final FactMapper factMapper;
    private final vip.newsclaw.memory.fact.repository.FactContradictionMapper contradictionMapper;

    /**
     * Probe facts by entity name (subject or object match).
     */
    public List<FactEntity> probe(Long agentId, String entity) {
        return probe(agentId, entity, null);
    }

    /** Owner-scoped probe: shared facts plus this owner's personal facts. */
    public List<FactEntity> probe(Long agentId, String entity, String ownerKey) {
        return factMapper.selectList(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getDeleted, 0)
                        .and(w -> w.like(FactEntity::getSubject, entity)
                                .or().like(FactEntity::getObjectValue, entity))
                        .and(q -> applyVisibility(q, ownerKey))
                        .orderByDesc(FactEntity::getTrust)
                        .last("LIMIT 20"));
    }

    /**
     * List unresolved contradictions for an agent.
     */
    public List<FactContradictionEntity> listContradictions(Long agentId) {
        return listContradictions(agentId, null);
    }

    /**
     * Owner-scoped contradiction list. Contradiction rows predate owner/scope
     * columns, so visibility is derived from both referenced fact projections.
     */
    public List<FactContradictionEntity> listContradictions(Long agentId, String ownerKey) {
        List<FactContradictionEntity> rows = contradictionMapper.selectList(
                new LambdaQueryWrapper<FactContradictionEntity>()
                        .eq(FactContradictionEntity::getAgentId, agentId)
                        .isNull(FactContradictionEntity::getResolution)
                        .eq(FactContradictionEntity::getDeleted, 0)
                        .orderByDesc(FactContradictionEntity::getCreateTime)
                        .last("LIMIT 50"));
        if (rows.isEmpty()) return rows;
        return rows.stream().filter(row -> {
            FactEntity a = row.getFactAId() == null ? null : factMapper.selectOne(
                    new LambdaQueryWrapper<FactEntity>().eq(FactEntity::getId, row.getFactAId())
                            .eq(FactEntity::getAgentId, agentId).eq(FactEntity::getDeleted, 0)
                            .and(q -> applyVisibility(q, ownerKey)));
            FactEntity b = row.getFactBId() == null ? null : factMapper.selectOne(
                    new LambdaQueryWrapper<FactEntity>().eq(FactEntity::getId, row.getFactBId())
                            .eq(FactEntity::getAgentId, agentId).eq(FactEntity::getDeleted, 0)
                            .and(q -> applyVisibility(q, ownerKey)));
            return a != null && b != null;
        }).toList();
    }

    /**
     * Recall relevant facts for a query (used by FactMemoryProvider.prefetch).
     */
    public List<FactEntity> recallRelevant(Long agentId, String query) {
        return recallRelevant(agentId, query, null);
    }

    /**
     * Owner-scoped recall: returns facts visible to {@code ownerKey} — shared
     * (TEAM / GLOBAL) facts plus this owner's PERSONAL facts. A null ownerKey
     * means shared-only. Keeps one user's recalled facts out of another user's
     * prompt when a single agent is shared across end-users.
     */
    public List<FactEntity> recallRelevant(Long agentId, String query, String ownerKey) {
        return factMapper.selectList(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getDeleted, 0)
                        .and(w -> w.like(FactEntity::getSubject, query)
                                .or().like(FactEntity::getObjectValue, query)
                                .or().like(FactEntity::getPredicate, query))
                        .and(s -> {
                            applyVisibility(s, ownerKey);
                        })
                        .orderByDesc(FactEntity::getTrust)
                        .last("LIMIT 10"));
    }

    /**
     * Bump use_count for fact IDs (the ONLY writer of accumulated columns).
     */
    public void bumpUseCount(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        factMapper.bumpUseCount(ids, LocalDateTime.now());
    }

    private static void applyVisibility(LambdaQueryWrapper<FactEntity> query, String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            query.in(FactEntity::getScope, MemoryScope.TEAM, MemoryScope.GLOBAL)
                    .and(w -> w.isNull(FactEntity::getOwnerKey).or().eq(FactEntity::getOwnerKey, ""));
        } else {
            query.and(w -> w.in(FactEntity::getScope, MemoryScope.TEAM, MemoryScope.GLOBAL)
                    .or(p -> p.eq(FactEntity::getScope, MemoryScope.PERSONAL)
                            .eq(FactEntity::getOwnerKey, ownerKey)));
        }
    }
}

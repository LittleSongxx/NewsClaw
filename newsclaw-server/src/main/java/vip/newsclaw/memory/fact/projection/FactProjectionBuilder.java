package vip.newsclaw.memory.fact.projection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.newsclaw.memory.MemoryProperties;
import vip.newsclaw.memory.fact.extraction.CompositeEntityExtractor;
import vip.newsclaw.memory.fact.extraction.ExtractedFact;
import vip.newsclaw.memory.fact.model.FactEntity;
import vip.newsclaw.memory.fact.repository.FactMapper;
import vip.newsclaw.workspace.document.WorkspaceFileService;
import vip.newsclaw.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the fact projection from canonical sources.
 * <p>
 * Derived columns are overwritten; accumulated columns (use_count, last_used_at)
 * are preserved via select-then-update keyed on (agent_id, source_ref).
 * <p>
 * Only this class may write derived columns to mate_fact (core invariant).
 * Uses MyBatis Plus CRUD (dialect-safe for both H2 and MySQL).
 *
 * @author NewsClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactProjectionBuilder {

    private final FactMapper factMapper;
    private final WorkspaceFileService workspaceFileService;
    private final CompositeEntityExtractor extractor;
    private final MemoryProperties properties;

    /**
     * Full rebuild for an agent. Extracts facts from all canonical sources,
     * upserts derived columns, and soft-deletes stale entries.
     */
    public int rebuildAll(Long agentId) {
        if (!properties.getFact().isProjectionEnabled()) {
            log.debug("[FactProjection] Projection disabled, skipping rebuildAll for agent={}", agentId);
            return 0;
        }

        int total = rebuildBucket(agentId, null);

        // PERSONAL facts have the same source_ref (for example
        // MEMORY.md#preferred_language) for every owner.  Rebuilding them in
        // the shared bucket would make one user's projection overwrite (and
        // eventually delete) another user's rows, so process each owner as an
        // independent projection namespace.
        java.util.Set<String> owners = workspaceFileService.listPersonalFiles(agentId).stream()
                .map(WorkspaceFileEntity::getOwnerKey)
                .filter(java.util.Objects::nonNull)
                .filter(owner -> !owner.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        for (String owner : owners) {
            total += rebuildBucket(agentId, owner);
        }

        log.info("[FactProjection] rebuildAll: agent={}, facts={}", agentId, total);
        return total;
    }

    private int rebuildBucket(Long agentId, String ownerKey) {
        List<ExtractedFact> facts = new ArrayList<>();
        List<WorkspaceFileEntity> files = ownerKey == null
                ? workspaceFileService.listVisibleFiles(agentId, null)
                : workspaceFileService.listPersonalFiles(agentId).stream()
                        .filter(f -> ownerKey.equals(f.getOwnerKey()))
                        .toList();
        for (WorkspaceFileEntity file : files) {
            String filename = file.getFilename();
            if (filename == null) continue;
            if ((filename.startsWith("structured/") && filename.endsWith(".md"))
                    || "MEMORY.md".equals(filename)) {
                WorkspaceFileEntity full = ownerKey == null
                        ? workspaceFileService.getFile(agentId, filename)
                        : workspaceFileService.getMemoryFile(agentId, filename, ownerKey);
                if (full != null && full.getContent() != null && !full.getContent().isBlank()) {
                    facts.addAll(extractor.extract(agentId, filename, full.getContent()));
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        List<String> keepRefs = facts.stream().map(ExtractedFact::sourceRef).toList();
        for (ExtractedFact fact : facts) {
            upsertDerived(agentId, fact, ownerKey, now);
        }
        if (ownerKey == null) {
            if (!keepRefs.isEmpty()) factMapper.deleteByAgentIdAndSourceRefNotIn(agentId, keepRefs, now);
            else factMapper.deleteAllByAgentId(agentId, now);
        } else {
            LambdaUpdateWrapper<FactEntity> stale = new LambdaUpdateWrapper<FactEntity>()
                    .eq(FactEntity::getAgentId, agentId)
                    .eq(FactEntity::getScope, vip.newsclaw.memory.identity.MemoryScope.PERSONAL)
                    .eq(FactEntity::getOwnerKey, ownerKey)
                    .eq(FactEntity::getDeleted, 0);
            if (keepRefs.isEmpty()) stale.set(FactEntity::getDeleted, 1);
            else stale.notIn(FactEntity::getSourceRef, keepRefs).set(FactEntity::getDeleted, 1);
            stale.set(FactEntity::getUpdateTime, now);
            factMapper.update(null, stale);
        }
        return facts.size();
    }

    /**
     * Incremental rebuild for a single file change.
     */
    public int rebuildOne(Long agentId, String filename, String content) {
        return rebuildOne(agentId, filename, content, null);
    }

    /** Incremental rebuild for one owner-scoped file. */
    public int rebuildOne(Long agentId, String filename, String content, String ownerKey) {
        if (!properties.getFact().isProjectionEnabled()) return 0;

        List<ExtractedFact> facts = extractor.extract(agentId, filename, content);
        LocalDateTime now = LocalDateTime.now();
        for (ExtractedFact fact : facts) {
            upsertDerived(agentId, fact, ownerKey, now);
        }
        if (ownerKey == null) {
            factMapper.deleteStaleForSource(agentId, filename,
                    facts.stream().map(ExtractedFact::sourceRef).toList(), now);
        } else {
            List<String> keep = facts.stream().map(ExtractedFact::sourceRef).toList();
            LambdaUpdateWrapper<FactEntity> stale = new LambdaUpdateWrapper<FactEntity>()
                    .eq(FactEntity::getAgentId, agentId)
                    .eq(FactEntity::getScope, vip.newsclaw.memory.identity.MemoryScope.PERSONAL)
                    .eq(FactEntity::getOwnerKey, ownerKey)
                    .eq(FactEntity::getDeleted, 0)
                    .and(w -> w.eq(FactEntity::getSourceRef, filename)
                            .or().likeRight(FactEntity::getSourceRef, filename + "#"));
            if (keep.isEmpty()) stale.set(FactEntity::getDeleted, 1);
            else stale.notIn(FactEntity::getSourceRef, keep).set(FactEntity::getDeleted, 1);
            stale.set(FactEntity::getUpdateTime, now);
            factMapper.update(null, stale);
        }
        log.debug("[FactProjection] rebuildOne: agent={}, file={}, facts={}", agentId, filename, facts.size());
        return facts.size();
    }

    /**
     * Dialect-safe upsert: select by (agent_id, source_ref), then insert or update.
     * Preserves accumulated columns (use_count, last_used_at) on update.
     */
    private void upsertDerived(Long agentId, ExtractedFact fact, String ownerKey, LocalDateTime now) {
        LambdaQueryWrapper<FactEntity> identity = new LambdaQueryWrapper<FactEntity>()
                .eq(FactEntity::getAgentId, agentId)
                .eq(FactEntity::getSourceRef, fact.sourceRef());
        if (ownerKey == null) {
            identity.in(FactEntity::getScope,
                    vip.newsclaw.memory.identity.MemoryScope.TEAM,
                    vip.newsclaw.memory.identity.MemoryScope.GLOBAL)
                    .and(w -> w.isNull(FactEntity::getOwnerKey).or().eq(FactEntity::getOwnerKey, ""));
        } else {
            identity.eq(FactEntity::getScope, vip.newsclaw.memory.identity.MemoryScope.PERSONAL)
                    .eq(FactEntity::getOwnerKey, ownerKey);
        }
        FactEntity existing = factMapper.selectOne(identity.last("LIMIT 1"));

        if (existing != null) {
            // Update derived columns only; preserve accumulated columns
            existing.setCategory(fact.category());
            existing.setSubject(fact.subject());
            existing.setPredicate(fact.predicate());
            existing.setObjectValue(fact.objectValue());
            existing.setConfidence(fact.confidence());
            existing.setExtractedBy(fact.extractedBy());
            existing.setOwnerKey(ownerKey);
            existing.setScope(ownerKey == null
                    ? vip.newsclaw.memory.identity.MemoryScope.TEAM
                    : vip.newsclaw.memory.identity.MemoryScope.PERSONAL);
            // Trust derived from canonical feedback metadata, then time-decayed
            double baseTrust = fact.trust();
            existing.setTrust(applyTimeDecay(baseTrust, existing.getUpdateTime(), now));
            existing.setUpdateTime(now);
            existing.setDeleted(0); // un-delete if previously soft-deleted
            factMapper.updateById(existing);
        } else {
            FactEntity entity = new FactEntity();
            entity.setAgentId(agentId);
            entity.setSourceRef(fact.sourceRef());
            entity.setCategory(fact.category());
            entity.setSubject(fact.subject());
            entity.setPredicate(fact.predicate());
            entity.setObjectValue(fact.objectValue());
            entity.setConfidence(fact.confidence());
            entity.setTrust(fact.trust());
            entity.setUseCount(0);
            entity.setExtractedBy(fact.extractedBy());
            entity.setOwnerKey(ownerKey);
            entity.setScope(ownerKey == null
                    ? vip.newsclaw.memory.identity.MemoryScope.TEAM
                    : vip.newsclaw.memory.identity.MemoryScope.PERSONAL);
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            entity.setDeleted(0);
            factMapper.insert(entity);
        }
    }

    /**
     * Apply exponential time decay to trust score.
     * Formula: trust * 2^(-daysSinceLastUpdate / halfLifeDays)
     * Clamped to [0, 1].
     */
    private double applyTimeDecay(Double currentTrust, LocalDateTime lastUpdate, LocalDateTime now) {
        if (currentTrust == null) return 0.5;
        if (lastUpdate == null) return currentTrust;
        int halfLifeDays = properties.getFact().getTrustHalfLifeDays();
        if (halfLifeDays <= 0) return currentTrust; // decay disabled
        long daysDiff = java.time.Duration.between(lastUpdate, now).toDays();
        if (daysDiff <= 0) return currentTrust;
        double decayed = currentTrust * Math.pow(2.0, -(double) daysDiff / halfLifeDays);
        return Math.max(0.0, Math.min(1.0, decayed));
    }
}

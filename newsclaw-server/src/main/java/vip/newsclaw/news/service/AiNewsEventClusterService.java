package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsEventClusterDetail;
import vip.newsclaw.news.model.AiNewsEventClusterEntity;
import vip.newsclaw.news.model.AiNewsEventClusterLineageEntity;
import vip.newsclaw.news.model.AiNewsEventClusterMemberEntity;
import vip.newsclaw.news.model.AiNewsEventClusterReviewEntity;
import vip.newsclaw.news.model.AiNewsEventClusterVersionEntity;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.repository.AiNewsEventClusterLineageMapper;
import vip.newsclaw.news.repository.AiNewsEventClusterMapper;
import vip.newsclaw.news.repository.AiNewsEventClusterMemberMapper;
import vip.newsclaw.news.repository.AiNewsEventClusterReviewMapper;
import vip.newsclaw.news.repository.AiNewsEventClusterVersionMapper;
import vip.newsclaw.news.repository.AiNewsEventMapper;
import vip.newsclaw.news.repository.AiNewsEvidenceMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Versioned online event clustering and manual merge/split governance.
 *
 * <p>Automatic assignment is deliberately precision-first. Borderline pairs
 * remain separate clusters and create a durable review proposal. Every change
 * writes a full membership snapshot and manual merge/split lineage, so an
 * auditor can reproduce which event identity was visible at any version.</p>
 */
@Service
public class AiNewsEventClusterService {

    private static final String ACTIVE = "active";
    private static final String MERGED = "merged";
    private static final String PENDING = "PENDING";

    private final AiNewsEventClusteringProperties properties;
    private final AiNewsEventClusterScorer scorer;
    private final AiNewsEventClusterMapper clusterMapper;
    private final AiNewsEventClusterVersionMapper versionMapper;
    private final AiNewsEventClusterMemberMapper memberMapper;
    private final AiNewsEventClusterLineageMapper lineageMapper;
    private final AiNewsEventClusterReviewMapper reviewMapper;
    private final AiNewsEventMapper eventMapper;
    private final AiNewsEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public AiNewsEventClusterService(AiNewsEventClusteringProperties properties,
                                     AiNewsEventClusterScorer scorer,
                                     AiNewsEventClusterMapper clusterMapper,
                                     AiNewsEventClusterVersionMapper versionMapper,
                                     AiNewsEventClusterMemberMapper memberMapper,
                                     AiNewsEventClusterLineageMapper lineageMapper,
                                     AiNewsEventClusterReviewMapper reviewMapper,
                                     AiNewsEventMapper eventMapper,
                                     AiNewsEvidenceMapper evidenceMapper,
                                     ObjectMapper objectMapper,
                                     MeterRegistry meterRegistry) {
        this.properties = properties;
        this.scorer = scorer;
        this.clusterMapper = clusterMapper;
        this.versionMapper = versionMapper;
        this.memberMapper = memberMapper;
        this.lineageMapper = lineageMapper;
        this.reviewMapper = reviewMapper;
        this.eventMapper = eventMapper;
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public Assignment assign(AiNewsEventEntity event) {
        Timer.Sample timer = Timer.start(meterRegistry);
        String outcome = "disabled";
        try {
            if (!properties.isEnabled()) return Assignment.disabled();
            requireEvent(event);
            long workspaceId = event.getWorkspaceId();
            List<AiNewsEventClusterMemberEntity> existing = memberMapper
                    .selectCurrentMemberships(workspaceId, event.getId());
            if (existing.size() > 1) {
                outcome = "invariant_error";
                throw new IllegalStateException("event belongs to multiple active cluster versions");
            }
            if (existing.size() == 1) {
                AiNewsEventClusterMemberEntity member = existing.getFirst();
                outcome = "already_assigned";
                return new Assignment(member.getClusterId(), member.getClusterVersionId(),
                        "ALREADY_ASSIGNED", number(member.getMembershipScore()), false, null);
            }

            List<Candidate> candidates = candidates(event);
            Candidate automatic = candidates.stream()
                    .filter(item -> item.score().automaticLink()).findFirst().orElse(null);
            if (automatic != null) {
                VersionWrite linked = appendToCluster(automatic.cluster().getId(), event,
                        automatic.score(), "ADD_MEMBER", "automatic online link", "system");
                outcome = automatic.score().assignmentOrigin().toLowerCase(Locale.ROOT);
                return new Assignment(linked.clusterId(), linked.versionId(),
                        automatic.score().assignmentOrigin(), automatic.score().value(), false, null);
            }

            VersionWrite singleton = createSingleton(event, "SINGLETON", "new first story", "system");
            Candidate reviewCandidate = candidates.stream()
                    .filter(item -> item.score().reviewSuggested()).findFirst().orElse(null);
            if (reviewCandidate == null) {
                outcome = "new_singleton";
                return new Assignment(singleton.clusterId(), singleton.versionId(),
                        "SINGLETON", 1.0D, false, null);
            }
            AiNewsEventClusterReviewEntity review = createReview(event, singleton.clusterId(),
                    reviewCandidate.cluster().getId(), reviewCandidate.score());
            outcome = "review_suggested";
            return new Assignment(singleton.clusterId(), singleton.versionId(),
                    "SINGLETON_REVIEW", reviewCandidate.score().value(), true, review.getId());
        } finally {
            meterRegistry.counter("newsclaw.ai_news.clustering.assignments", "outcome", outcome)
                    .increment();
            timer.stop(Timer.builder("newsclaw.ai_news.clustering.duration")
                    .description("AI-news online cluster assignment latency")
                    .tag("operation", "assign")
                    .register(meterRegistry));
        }
    }

    public IPage<AiNewsEventClusterEntity> page(Long workspaceId, int page, int size,
                                                 String status) {
        long ws = workspace(workspaceId);
        LambdaQueryWrapper<AiNewsEventClusterEntity> query = new LambdaQueryWrapper<>() ;
        query.eq(AiNewsEventClusterEntity::getWorkspaceId, ws)
                .eq(AiNewsEventClusterEntity::getDeleted, 0);
        if (status != null && !status.isBlank()) {
            query.eq(AiNewsEventClusterEntity::getStatus, status.trim().toLowerCase(Locale.ROOT));
        }
        query.orderByDesc(AiNewsEventClusterEntity::getUpdateTime)
                .orderByAsc(AiNewsEventClusterEntity::getId);
        IPage<AiNewsEventClusterEntity> result = clusterMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 100)), query);
        populateClusterProjection(ws, result.getRecords());
        return result;
    }

    public AiNewsEventClusterDetail detail(Long workspaceId, Long clusterId) {
        long ws = workspace(workspaceId);
        AiNewsEventClusterEntity cluster = requireCluster(ws, clusterId, false);
        AiNewsEventClusterVersionEntity current = requireVersion(ws, cluster.getCurrentVersionId());
        List<AiNewsEventClusterMemberEntity> memberships = currentMembers(ws, cluster, current);
        List<AiNewsEventEntity> events = orderedEvents(memberships);
        List<AiNewsEventClusterVersionEntity> versions = versionMapper.selectList(
                new LambdaQueryWrapper<AiNewsEventClusterVersionEntity>()
                        .eq(AiNewsEventClusterVersionEntity::getWorkspaceId, ws)
                        .eq(AiNewsEventClusterVersionEntity::getClusterId, cluster.getId())
                        .eq(AiNewsEventClusterVersionEntity::getDeleted, 0)
                        .orderByDesc(AiNewsEventClusterVersionEntity::getVersionNo));
        List<AiNewsEventClusterLineageEntity> lineage = lineageMapper.selectList(
                new LambdaQueryWrapper<AiNewsEventClusterLineageEntity>()
                        .eq(AiNewsEventClusterLineageEntity::getWorkspaceId, ws)
                        .eq(AiNewsEventClusterLineageEntity::getDeleted, 0)
                        .and(value -> value.eq(AiNewsEventClusterLineageEntity::getFromClusterId,
                                        cluster.getId())
                                .or().eq(AiNewsEventClusterLineageEntity::getToClusterId,
                                        cluster.getId()))
                        .orderByDesc(AiNewsEventClusterLineageEntity::getCreateTime));
        List<AiNewsEventClusterReviewEntity> reviews = reviewMapper.selectList(
                new LambdaQueryWrapper<AiNewsEventClusterReviewEntity>()
                        .eq(AiNewsEventClusterReviewEntity::getWorkspaceId, ws)
                        .eq(AiNewsEventClusterReviewEntity::getDeleted, 0)
                        .and(value -> value.eq(AiNewsEventClusterReviewEntity::getSourceClusterId,
                                        cluster.getId())
                                .or().eq(AiNewsEventClusterReviewEntity::getCandidateClusterId,
                                        cluster.getId()))
                        .orderByDesc(AiNewsEventClusterReviewEntity::getCreateTime));
        populateClusterProjection(ws, List.of(cluster));
        return new AiNewsEventClusterDetail(cluster, current, memberships, events,
                versions, lineage, reviews);
    }

    public List<AiNewsEventClusterReviewEntity> reviews(Long workspaceId, String status, int limit) {
        String token = status == null || status.isBlank() ? PENDING : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(PENDING, "APPROVED", "REJECTED", "SUPERSEDED").contains(token)) {
            throw new NewsClawException(400, "未知聚类复核状态");
        }
        return reviewMapper.selectList(new LambdaQueryWrapper<AiNewsEventClusterReviewEntity>()
                .eq(AiNewsEventClusterReviewEntity::getWorkspaceId, workspace(workspaceId))
                .eq(AiNewsEventClusterReviewEntity::getStatus, token)
                .eq(AiNewsEventClusterReviewEntity::getDeleted, 0)
                .orderByAsc(AiNewsEventClusterReviewEntity::getCreateTime)
                .last("LIMIT " + Math.min(Math.max(1, limit), 200)));
    }

    @Transactional
    public AiNewsEventClusterDetail merge(Long workspaceId, Collection<Long> requestedClusterIds,
                                           String reviewer, String note) {
        long ws = workspace(workspaceId);
        String actor = requiredActor(reviewer);
        List<Long> clusterIds = normalizedIds(requestedClusterIds, 2, 20, "clusterIds");
        List<AiNewsEventClusterEntity> clusters = new ArrayList<>();
        for (Long id : clusterIds) {
            AiNewsEventClusterEntity cluster = clusterMapper.selectForUpdate(ws, id);
            if (cluster == null || !ACTIVE.equals(cluster.getStatus())) {
                throw new NewsClawException(409, "只能合并当前 active 的事件簇: " + id);
            }
            clusters.add(cluster);
        }
        AiNewsEventClusterEntity target = clusters.getFirst();
        String operationId = UUID.randomUUID().toString();
        Map<Long, MemberSeed> union = new LinkedHashMap<>();
        Map<Long, Long> priorVersions = new LinkedHashMap<>();
        for (AiNewsEventClusterEntity cluster : clusters) {
            AiNewsEventClusterVersionEntity version = requireVersion(ws, cluster.getCurrentVersionId());
            priorVersions.put(cluster.getId(), version.getId());
            for (AiNewsEventClusterMemberEntity member : currentMembers(ws, cluster, version)) {
                boolean moved = !Objects.equals(cluster.getId(), target.getId());
                union.putIfAbsent(member.getEventId(), new MemberSeed(member.getEventId(),
                        moved ? 1.0D : number(member.getMembershipScore()),
                        moved ? "MANUAL_MERGE" : member.getAssignmentOrigin(),
                        moved ? manualBreakdown("merge", operationId) : member.getScoreBreakdownJson(),
                        member.getAssignedAt()));
            }
        }
        VersionWrite targetVersion = writeVersion(target, new ArrayList<>(union.values()),
                "MERGE", bounded(note, 1000), actor);
        LocalDateTime now = LocalDateTime.now();
        for (AiNewsEventClusterEntity source : clusters) {
            if (Objects.equals(source.getId(), target.getId())) continue;
            source.setStatus(MERGED);
            source.setUpdateTime(now);
            clusterMapper.updateById(source);
            writeLineage(ws, operationId, "MERGE", source.getId(),
                    priorVersions.get(source.getId()), target.getId(), targetVersion.versionId(),
                    note, actor);
        }
        supersedeReviews(ws, clusterIds);
        meterRegistry.counter("newsclaw.ai_news.clustering.manual", "operation", "merge").increment();
        return detail(ws, target.getId());
    }

    @Transactional
    public AiNewsEventClusterDetail split(Long workspaceId, Long sourceClusterId,
                                           Collection<Long> requestedEventIds,
                                           String reviewer, String note) {
        long ws = workspace(workspaceId);
        String actor = requiredActor(reviewer);
        AiNewsEventClusterEntity source = clusterMapper.selectForUpdate(ws, requiredId(sourceClusterId,
                "clusterId"));
        if (source == null || !ACTIVE.equals(source.getStatus())) {
            throw new NewsClawException(409, "只能拆分当前 active 的事件簇");
        }
        AiNewsEventClusterVersionEntity prior = requireVersion(ws, source.getCurrentVersionId());
        List<AiNewsEventClusterMemberEntity> current = currentMembers(ws, source, prior);
        List<Long> eventIds = normalizedIds(requestedEventIds, 1,
                Math.max(1, current.size() - 1), "eventIds");
        Set<Long> selected = Set.copyOf(eventIds);
        Set<Long> available = current.stream().map(AiNewsEventClusterMemberEntity::getEventId)
                .collect(Collectors.toSet());
        if (!available.containsAll(selected) || selected.size() >= available.size()) {
            throw new NewsClawException(400, "拆分成员必须是当前事件簇的非空真子集");
        }
        String operationId = UUID.randomUUID().toString();
        List<MemberSeed> remaining = new ArrayList<>();
        List<MemberSeed> child = new ArrayList<>();
        for (AiNewsEventClusterMemberEntity member : current) {
            if (selected.contains(member.getEventId())) {
                child.add(new MemberSeed(member.getEventId(), 1.0D, "MANUAL_SPLIT",
                        manualBreakdown("split", operationId), member.getAssignedAt()));
            } else {
                remaining.add(seed(member));
            }
        }
        writeVersion(source, remaining, "SPLIT_REMAINDER", bounded(note, 1000), actor);
        AiNewsEventEntity childRepresentative = requireEvents(ws,
                child.stream().map(MemberSeed::eventId).toList()).getFirst();
        VersionWrite childVersion = createCluster(childRepresentative, child, "MANUAL_SPLIT",
                "split from cluster " + source.getId() + suffix(note), actor,
                sha256("split|" + ws + "|" + source.getId() + "|" + operationId));
        writeLineage(ws, operationId, "SPLIT", source.getId(), prior.getId(),
                childVersion.clusterId(), childVersion.versionId(), note, actor);
        supersedeReviews(ws, List.of(source.getId(), childVersion.clusterId()));
        meterRegistry.counter("newsclaw.ai_news.clustering.manual", "operation", "split").increment();
        return detail(ws, childVersion.clusterId());
    }

    @Transactional
    public AiNewsEventClusterReviewEntity resolveReview(Long workspaceId, Long reviewId,
                                                         String decision, String reviewer,
                                                         String note) {
        long ws = workspace(workspaceId);
        String actor = requiredActor(reviewer);
        AiNewsEventClusterReviewEntity review = reviewMapper.selectForUpdate(ws,
                requiredId(reviewId, "reviewId"));
        if (review == null || !PENDING.equals(review.getStatus())) {
            throw new NewsClawException(404, "待处理聚类复核不存在");
        }
        String token = decision == null ? "" : decision.trim().toLowerCase(Locale.ROOT);
        if ("approve".equals(token) || "approved".equals(token) || "merge".equals(token)) {
            merge(ws, List.of(review.getSourceClusterId(), review.getCandidateClusterId()), actor,
                    firstNonBlank(note, "approved cluster-link review " + review.getId()));
            review.setStatus("APPROVED");
        } else if ("reject".equals(token) || "rejected".equals(token)
                || "keep_separate".equals(token)) {
            review.setStatus("REJECTED");
        } else {
            throw new NewsClawException(400, "decision 必须为 approve/merge 或 reject/keep_separate");
        }
        review.setReviewer(actor);
        review.setReviewNote(bounded(note, 1000));
        review.setResolvedAt(LocalDateTime.now());
        review.setUpdateTime(LocalDateTime.now());
        reviewMapper.updateById(review);
        return review;
    }

    @Transactional
    public BackfillResult backfill(Long workspaceId, int requestedLimit) {
        long ws = workspace(workspaceId);
        int limit = Math.min(Math.max(1, requestedLimit), 500);
        List<AiNewsEventEntity> events = eventMapper.selectList(
                new LambdaQueryWrapper<AiNewsEventEntity>()
                        .eq(AiNewsEventEntity::getWorkspaceId, ws)
                        .eq(AiNewsEventEntity::getDeleted, 0)
                        .orderByAsc(AiNewsEventEntity::getSourcePublishedAt)
                        .orderByAsc(AiNewsEventEntity::getDiscoveredAt)
                        .orderByAsc(AiNewsEventEntity::getId)
                        .last("LIMIT " + limit));
        int assigned = 0;
        int alreadyAssigned = 0;
        int reviewSuggested = 0;
        for (AiNewsEventEntity event : events) {
            Assignment result = assign(event);
            if ("ALREADY_ASSIGNED".equals(result.decision())) alreadyAssigned++;
            else assigned++;
            if (result.reviewRequired()) reviewSuggested++;
        }
        return new BackfillResult(events.size(), assigned, alreadyAssigned, reviewSuggested);
    }

    /** Batch projection for the existing event workbench without changing event ownership. */
    public void populateEventProjection(long workspaceId, List<AiNewsEventEntity> events) {
        if (!properties.isEnabled() || events == null || events.isEmpty()) return;
        List<Long> eventIds = events.stream().map(AiNewsEventEntity::getId)
                .filter(Objects::nonNull).distinct().toList();
        if (eventIds.isEmpty()) return;
        List<AiNewsEventClusterMemberEntity> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<AiNewsEventClusterMemberEntity>()
                        .eq(AiNewsEventClusterMemberEntity::getWorkspaceId, workspaceId)
                        .in(AiNewsEventClusterMemberEntity::getEventId, eventIds)
                        .eq(AiNewsEventClusterMemberEntity::getDeleted, 0));
        Set<Long> clusterIds = memberships.stream().map(AiNewsEventClusterMemberEntity::getClusterId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, AiNewsEventClusterEntity> clusters = clusterIds.isEmpty() ? Map.of()
                : clusterMapper.selectBatchIds(clusterIds).stream()
                .filter(item -> Objects.equals(item.getWorkspaceId(), workspaceId)
                        && ACTIVE.equals(item.getStatus()) && !nonzero(item.getDeleted()))
                .collect(Collectors.toMap(AiNewsEventClusterEntity::getId, Function.identity()));
        Map<Long, AiNewsEventClusterMemberEntity> currentByEvent = memberships.stream()
                .filter(member -> {
                    AiNewsEventClusterEntity cluster = clusters.get(member.getClusterId());
                    return cluster != null && Objects.equals(cluster.getCurrentVersionId(),
                            member.getClusterVersionId());
                })
                .sorted(Comparator.comparing(AiNewsEventClusterMemberEntity::getClusterId))
                .collect(Collectors.toMap(AiNewsEventClusterMemberEntity::getEventId,
                        Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Set<Long> versionIds = currentByEvent.values().stream()
                .map(AiNewsEventClusterMemberEntity::getClusterVersionId).collect(Collectors.toSet());
        Map<Long, AiNewsEventClusterVersionEntity> versions = versionIds.isEmpty() ? Map.of()
                : versionMapper.selectBatchIds(versionIds).stream().collect(Collectors.toMap(
                        AiNewsEventClusterVersionEntity::getId, Function.identity()));
        Set<Long> pendingEvents = reviewMapper.selectList(
                        new LambdaQueryWrapper<AiNewsEventClusterReviewEntity>()
                                .eq(AiNewsEventClusterReviewEntity::getWorkspaceId, workspaceId)
                                .in(AiNewsEventClusterReviewEntity::getEventId, eventIds)
                                .eq(AiNewsEventClusterReviewEntity::getStatus, PENDING)
                                .eq(AiNewsEventClusterReviewEntity::getDeleted, 0))
                .stream().map(AiNewsEventClusterReviewEntity::getEventId).collect(Collectors.toSet());
        for (AiNewsEventEntity event : events) {
            AiNewsEventClusterMemberEntity member = currentByEvent.get(event.getId());
            if (member == null) continue;
            AiNewsEventClusterVersionEntity version = versions.get(member.getClusterVersionId());
            event.setClusterId(member.getClusterId());
            event.setClusterVersionId(member.getClusterVersionId());
            event.setClusterMemberCount(version == null ? null : version.getMemberCount());
            event.setClusterAssignmentOrigin(member.getAssignmentOrigin());
            event.setClusterAssignmentScore(member.getMembershipScore());
            event.setClusterReviewRequired(pendingEvents.contains(event.getId()));
        }
    }

    private List<Candidate> candidates(AiNewsEventEntity incoming) {
        long ws = incoming.getWorkspaceId();
        List<AiNewsEventClusterEntity> clusters = clusterMapper.selectList(
                new LambdaQueryWrapper<AiNewsEventClusterEntity>()
                        .eq(AiNewsEventClusterEntity::getWorkspaceId, ws)
                        .eq(AiNewsEventClusterEntity::getStatus, ACTIVE)
                        .isNotNull(AiNewsEventClusterEntity::getCurrentVersionId)
                        .eq(AiNewsEventClusterEntity::getDeleted, 0)
                        .orderByDesc(AiNewsEventClusterEntity::getUpdateTime)
                        .orderByAsc(AiNewsEventClusterEntity::getId)
                        .last("LIMIT " + scorer.effectiveMaxCandidates()));
        if (clusters.isEmpty()) return List.of();
        Map<Long, AiNewsEventClusterVersionEntity> versions = versionMapper.selectBatchIds(
                        clusters.stream().map(AiNewsEventClusterEntity::getCurrentVersionId).toList())
                .stream().filter(item -> Objects.equals(item.getWorkspaceId(), ws))
                .collect(Collectors.toMap(AiNewsEventClusterVersionEntity::getId, Function.identity()));
        List<Long> representativeIds = versions.values().stream()
                .map(AiNewsEventClusterVersionEntity::getRepresentativeEventId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, AiNewsEventEntity> events = eventMapper.selectBatchIds(representativeIds).stream()
                .filter(item -> Objects.equals(item.getWorkspaceId(), ws) && !nonzero(item.getDeleted()))
                .collect(Collectors.toMap(AiNewsEventEntity::getId, Function.identity()));
        List<Long> evidenceEventIds = new ArrayList<>(representativeIds);
        evidenceEventIds.add(incoming.getId());
        Map<Long, Set<String>> urls = evidenceUrls(ws, evidenceEventIds);
        AiNewsEventClusterScorer.EventDocument incomingDocument = document(incoming,
                urls.getOrDefault(incoming.getId(), Set.of()));
        List<Candidate> scored = new ArrayList<>();
        for (AiNewsEventClusterEntity cluster : clusters) {
            AiNewsEventClusterVersionEntity version = versions.get(cluster.getCurrentVersionId());
            AiNewsEventEntity representative = version == null ? null
                    : events.get(version.getRepresentativeEventId());
            if (representative == null) continue;
            AiNewsEventClusterScorer.Score score = scorer.score(incomingDocument,
                    document(representative, urls.getOrDefault(representative.getId(), Set.of())));
            if (score.value() > 0.0D) scored.add(new Candidate(cluster, version, score));
        }
        scored.sort(Comparator.comparingDouble((Candidate value) -> value.score().value()).reversed()
                .thenComparing(value -> value.cluster().getId()));
        return List.copyOf(scored);
    }

    private VersionWrite createSingleton(AiNewsEventEntity event, String origin,
                                         String reason, String createdBy) {
        MemberSeed seed = new MemberSeed(event.getId(), 1.0D, origin,
                writeJson(Map.of("decision", origin)), LocalDateTime.now());
        return createCluster(event, List.of(seed), origin, reason, createdBy,
                sha256("event|" + event.getWorkspaceId() + "|" + event.getId()));
    }

    private VersionWrite createCluster(AiNewsEventEntity representative, List<MemberSeed> members,
                                       String origin, String reason, String createdBy,
                                       String clusterKey) {
        long ws = representative.getWorkspaceId();
        AiNewsEventClusterEntity existing = clusterMapper.selectOne(
                new LambdaQueryWrapper<AiNewsEventClusterEntity>()
                        .eq(AiNewsEventClusterEntity::getWorkspaceId, ws)
                        .eq(AiNewsEventClusterEntity::getClusterKey, clusterKey));
        if (existing != null) {
            if (existing.getCurrentVersionId() == null) {
                throw new IllegalStateException("existing cluster has no current version");
            }
            return new VersionWrite(existing.getId(), existing.getCurrentVersionId());
        }
        AiNewsEventClusterEntity cluster = new AiNewsEventClusterEntity();
        cluster.setWorkspaceId(ws);
        cluster.setClusterKey(clusterKey);
        cluster.setStatus(ACTIVE);
        cluster.setCreatedOrigin(origin);
        cluster.setCreateTime(LocalDateTime.now());
        cluster.setUpdateTime(LocalDateTime.now());
        cluster.setDeleted(0);
        clusterMapper.insert(cluster);
        return writeVersion(cluster, members, "CREATE", reason, createdBy);
    }

    private VersionWrite appendToCluster(long clusterId, AiNewsEventEntity event,
                                         AiNewsEventClusterScorer.Score score,
                                         String changeType, String reason, String createdBy) {
        long ws = event.getWorkspaceId();
        AiNewsEventClusterEntity cluster = clusterMapper.selectForUpdate(ws, clusterId);
        if (cluster == null || !ACTIVE.equals(cluster.getStatus())) {
            throw new NewsClawException(409, "候选事件簇已变化，请重试聚类");
        }
        AiNewsEventClusterVersionEntity current = requireVersion(ws, cluster.getCurrentVersionId());
        List<MemberSeed> seeds = currentMembers(ws, cluster, current).stream()
                .map(AiNewsEventClusterService::seed).collect(Collectors.toCollection(ArrayList::new));
        if (seeds.stream().anyMatch(item -> Objects.equals(item.eventId(), event.getId()))) {
            return new VersionWrite(cluster.getId(), current.getId());
        }
        seeds.add(new MemberSeed(event.getId(), score.value(), score.assignmentOrigin(),
                writeJson(score.breakdown()), LocalDateTime.now()));
        return writeVersion(cluster, seeds, changeType, reason, createdBy);
    }

    private VersionWrite writeVersion(AiNewsEventClusterEntity suppliedCluster,
                                      List<MemberSeed> suppliedMembers,
                                      String changeType, String reason, String createdBy) {
        if (suppliedMembers == null || suppliedMembers.isEmpty()) {
            throw new IllegalArgumentException("cluster version requires members");
        }
        long ws = suppliedCluster.getWorkspaceId();
        AiNewsEventClusterEntity cluster = suppliedCluster.getCurrentVersionId() == null
                ? suppliedCluster : clusterMapper.selectForUpdate(ws, suppliedCluster.getId());
        if (cluster == null || !ACTIVE.equals(cluster.getStatus())) {
            throw new NewsClawException(409, "事件簇已不再 active");
        }
        Long expectedVersionId = cluster.getCurrentVersionId();
        int versionNo = 1;
        if (expectedVersionId != null) {
            AiNewsEventClusterVersionEntity prior = requireVersion(ws, expectedVersionId);
            versionNo = prior.getVersionNo() + 1;
        }
        Map<Long, MemberSeed> unique = new LinkedHashMap<>();
        suppliedMembers.stream().sorted(Comparator.comparing(MemberSeed::eventId))
                .forEach(item -> unique.putIfAbsent(item.eventId(), item));
        List<AiNewsEventEntity> events = requireEvents(ws, new ArrayList<>(unique.keySet()));
        AiNewsEventEntity representative = representative(events);
        TreeSet<String> entities = new TreeSet<>();
        events.forEach(item -> entities.addAll(parseEntities(item.getEntitiesJson())));
        List<LocalDateTime> sourceTimes = events.stream()
                .map(AiNewsEventEntity::getSourcePublishedAt).filter(Objects::nonNull).sorted().toList();

        AiNewsEventClusterVersionEntity version = new AiNewsEventClusterVersionEntity();
        version.setWorkspaceId(ws);
        version.setClusterId(cluster.getId());
        version.setVersionNo(versionNo);
        version.setChangeType(changeType);
        version.setRepresentativeEventId(representative.getId());
        version.setCanonicalTitle(bounded(firstNonBlank(representative.getTitle(), "Untitled event"), 512));
        version.setCategory(bounded(representative.getCategory(), 32));
        version.setEntitiesJson(writeJson(entities));
        version.setEarliestSourcePublishedAt(sourceTimes.isEmpty() ? null : sourceTimes.getFirst());
        version.setLatestSourcePublishedAt(sourceTimes.isEmpty() ? null : sourceTimes.getLast());
        version.setMemberCount(unique.size());
        version.setAlgorithmName(AiNewsEventClusterScorer.ALGORITHM_NAME);
        version.setAlgorithmVersion(AiNewsEventClusterScorer.ALGORITHM_VERSION);
        version.setFeatureVersion(AiNewsEventClusterScorer.FEATURE_VERSION);
        version.setConfigHash(scorer.configHash());
        version.setChangeReason(bounded(reason, 1000));
        version.setCreatedBy(bounded(createdBy, 256));
        version.setCreateTime(LocalDateTime.now());
        version.setDeleted(0);
        versionMapper.insert(version);

        for (MemberSeed seed : unique.values()) {
            AiNewsEventClusterMemberEntity member = new AiNewsEventClusterMemberEntity();
            member.setWorkspaceId(ws);
            member.setClusterId(cluster.getId());
            member.setClusterVersionId(version.getId());
            member.setEventId(seed.eventId());
            member.setMembershipScore(seed.score());
            member.setAssignmentOrigin(firstNonBlank(seed.origin(), "UNKNOWN"));
            member.setScoreBreakdownJson(seed.breakdownJson());
            member.setAssignedAt(seed.assignedAt() == null ? LocalDateTime.now() : seed.assignedAt());
            member.setCreateTime(LocalDateTime.now());
            member.setDeleted(0);
            memberMapper.insert(member);
        }
        if (clusterMapper.compareAndSetVersion(ws, cluster.getId(), expectedVersionId,
                version.getId()) != 1) {
            throw new NewsClawException(409, "事件簇版本发生并发变化，请重试");
        }
        cluster.setCurrentVersionId(version.getId());
        return new VersionWrite(cluster.getId(), version.getId());
    }

    private AiNewsEventClusterReviewEntity createReview(AiNewsEventEntity event,
                                                         long sourceClusterId,
                                                         long candidateClusterId,
                                                         AiNewsEventClusterScorer.Score score) {
        AiNewsEventClusterReviewEntity existing = reviewMapper.selectOne(
                new LambdaQueryWrapper<AiNewsEventClusterReviewEntity>()
                        .eq(AiNewsEventClusterReviewEntity::getWorkspaceId, event.getWorkspaceId())
                        .eq(AiNewsEventClusterReviewEntity::getEventId, event.getId())
                        .eq(AiNewsEventClusterReviewEntity::getCandidateClusterId, candidateClusterId)
                        .eq(AiNewsEventClusterReviewEntity::getStatus, PENDING)
                        .eq(AiNewsEventClusterReviewEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (existing != null) return existing;
        AiNewsEventClusterReviewEntity review = new AiNewsEventClusterReviewEntity();
        review.setWorkspaceId(event.getWorkspaceId());
        review.setEventId(event.getId());
        review.setSourceClusterId(sourceClusterId);
        review.setCandidateClusterId(candidateClusterId);
        review.setProposedAction("MERGE");
        review.setScore(score.value());
        review.setDecisionThreshold(scorer.effectiveAutoThreshold());
        review.setAlgorithmName(AiNewsEventClusterScorer.ALGORITHM_NAME);
        review.setAlgorithmVersion(AiNewsEventClusterScorer.ALGORITHM_VERSION);
        review.setFeatureVersion(AiNewsEventClusterScorer.FEATURE_VERSION);
        review.setConfigHash(scorer.configHash());
        review.setScoreBreakdownJson(writeJson(score.breakdown()));
        review.setStatus(PENDING);
        review.setCreateTime(LocalDateTime.now());
        review.setUpdateTime(LocalDateTime.now());
        review.setDeleted(0);
        reviewMapper.insert(review);
        return review;
    }

    private void populateClusterProjection(long workspaceId,
                                           List<AiNewsEventClusterEntity> clusters) {
        if (clusters == null || clusters.isEmpty()) return;
        List<Long> versionIds = clusters.stream().map(AiNewsEventClusterEntity::getCurrentVersionId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, AiNewsEventClusterVersionEntity> versions = versionIds.isEmpty() ? Map.of()
                : versionMapper.selectBatchIds(versionIds).stream().collect(Collectors.toMap(
                        AiNewsEventClusterVersionEntity::getId, Function.identity()));
        List<Long> clusterIds = clusters.stream().map(AiNewsEventClusterEntity::getId).toList();
        Map<Long, Long> reviewCounts = reviewMapper.selectList(
                        new LambdaQueryWrapper<AiNewsEventClusterReviewEntity>()
                                .eq(AiNewsEventClusterReviewEntity::getWorkspaceId, workspaceId)
                                .eq(AiNewsEventClusterReviewEntity::getStatus, PENDING)
                                .eq(AiNewsEventClusterReviewEntity::getDeleted, 0)
                                .and(value -> value.in(AiNewsEventClusterReviewEntity::getSourceClusterId,
                                                clusterIds)
                                        .or().in(AiNewsEventClusterReviewEntity::getCandidateClusterId,
                                                clusterIds)))
                .stream().flatMap(item -> java.util.stream.Stream.of(
                        item.getSourceClusterId(), item.getCandidateClusterId()))
                .filter(Objects::nonNull).filter(clusterIds::contains)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        for (AiNewsEventClusterEntity cluster : clusters) {
            AiNewsEventClusterVersionEntity version = versions.get(cluster.getCurrentVersionId());
            if (version != null) {
                cluster.setVersionNo(version.getVersionNo());
                cluster.setRepresentativeEventId(version.getRepresentativeEventId());
                cluster.setCanonicalTitle(version.getCanonicalTitle());
                cluster.setCategory(version.getCategory());
                cluster.setMemberCount(version.getMemberCount());
                cluster.setAlgorithmVersion(version.getAlgorithmVersion());
                cluster.setConfigHash(version.getConfigHash());
            }
            cluster.setPendingReviewCount(Math.toIntExact(reviewCounts.getOrDefault(cluster.getId(), 0L)));
        }
    }

    private List<AiNewsEventClusterMemberEntity> currentMembers(long workspaceId,
                                                                 AiNewsEventClusterEntity cluster,
                                                                 AiNewsEventClusterVersionEntity version) {
        if (!Objects.equals(cluster.getCurrentVersionId(), version.getId())) {
            throw new IllegalStateException("cluster/version pointer mismatch");
        }
        List<AiNewsEventClusterMemberEntity> members = memberMapper.selectList(
                new LambdaQueryWrapper<AiNewsEventClusterMemberEntity>()
                        .eq(AiNewsEventClusterMemberEntity::getWorkspaceId, workspaceId)
                        .eq(AiNewsEventClusterMemberEntity::getClusterId, cluster.getId())
                        .eq(AiNewsEventClusterMemberEntity::getClusterVersionId, version.getId())
                        .eq(AiNewsEventClusterMemberEntity::getDeleted, 0)
                        .orderByAsc(AiNewsEventClusterMemberEntity::getEventId));
        if (members.size() != version.getMemberCount()) {
            throw new IllegalStateException("cluster member_count does not match current snapshot");
        }
        return List.copyOf(members);
    }

    private List<AiNewsEventEntity> orderedEvents(List<AiNewsEventClusterMemberEntity> members) {
        if (members.isEmpty()) return List.of();
        Map<Long, AiNewsEventEntity> byId = eventMapper.selectBatchIds(
                        members.stream().map(AiNewsEventClusterMemberEntity::getEventId).toList())
                .stream().collect(Collectors.toMap(AiNewsEventEntity::getId, Function.identity()));
        return members.stream().map(member -> byId.get(member.getEventId()))
                .filter(Objects::nonNull).toList();
    }

    private List<AiNewsEventEntity> requireEvents(long workspaceId, Collection<Long> ids) {
        List<Long> unique = ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
        List<AiNewsEventEntity> events = eventMapper.selectBatchIds(unique).stream()
                .filter(item -> Objects.equals(item.getWorkspaceId(), workspaceId)
                        && !nonzero(item.getDeleted())).toList();
        if (events.size() != unique.size()) {
            throw new NewsClawException(409, "事件簇包含不存在或跨 workspace 的事件");
        }
        return events;
    }

    private Map<Long, Set<String>> evidenceUrls(long workspaceId, Collection<Long> eventIds) {
        List<Long> unique = eventIds.stream().filter(Objects::nonNull).distinct().toList();
        if (unique.isEmpty()) return Map.of();
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        for (AiNewsEvidenceEntity evidence : evidenceMapper.selectList(
                new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                        .eq(AiNewsEvidenceEntity::getWorkspaceId, workspaceId)
                        .in(AiNewsEvidenceEntity::getEventId, unique)
                        .eq(AiNewsEvidenceEntity::getDeleted, 0))) {
            String url = firstNonBlank(evidence.getFinalUrl(), evidence.getSourceUrl());
            if (url != null && !url.isBlank()) {
                result.computeIfAbsent(evidence.getEventId(), ignored -> new LinkedHashSet<>())
                        .add(url);
            }
        }
        return result;
    }

    private AiNewsEventClusterScorer.EventDocument document(AiNewsEventEntity event,
                                                              Set<String> urls) {
        return new AiNewsEventClusterScorer.EventDocument(event.getId(), event.getEventKey(),
                event.getTitle(), event.getSummary(), event.getCategory(),
                parseEntities(event.getEntitiesJson()), urls, event.getSourcePublishedAt(),
                event.getDiscoveredAt());
    }

    private Set<String> parseEntities(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        try {
            List<String> values = objectMapper.readValue(raw, new TypeReference<>() { });
            return values == null ? Set.of() : values.stream().filter(Objects::nonNull)
                    .map(String::trim).filter(value -> !value.isBlank())
                    .limit(100).collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private static AiNewsEventEntity representative(List<AiNewsEventEntity> events) {
        return events.stream().min(Comparator
                .comparingDouble((AiNewsEventEntity item) -> -number(item.getRankingScore()))
                .thenComparing(AiNewsEventClusterService::effectiveTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AiNewsEventEntity::getId)).orElseThrow();
    }

    private static LocalDateTime effectiveTime(AiNewsEventEntity event) {
        return event.getSourcePublishedAt() == null ? event.getDiscoveredAt()
                : event.getSourcePublishedAt();
    }

    private AiNewsEventClusterEntity requireCluster(long workspaceId, Long id, boolean activeOnly) {
        if (id == null || id <= 0) throw new NewsClawException(400, "clusterId 必须是有效 ID");
        AiNewsEventClusterEntity cluster = clusterMapper.selectById(id);
        if (cluster == null || !Objects.equals(cluster.getWorkspaceId(), workspaceId)
                || nonzero(cluster.getDeleted()) || activeOnly && !ACTIVE.equals(cluster.getStatus())) {
            throw new NewsClawException(404, "事件簇不存在或不属于当前 workspace");
        }
        if (cluster.getCurrentVersionId() == null) {
            throw new NewsClawException(409, "事件簇缺少当前版本");
        }
        return cluster;
    }

    private AiNewsEventClusterVersionEntity requireVersion(long workspaceId, Long id) {
        if (id == null) throw new IllegalStateException("cluster current_version_id is null");
        AiNewsEventClusterVersionEntity version = versionMapper.selectById(id);
        if (version == null || !Objects.equals(version.getWorkspaceId(), workspaceId)
                || nonzero(version.getDeleted())) {
            throw new IllegalStateException("cluster version is missing or cross-workspace");
        }
        return version;
    }

    private void writeLineage(long workspaceId, String operationId, String type,
                              long fromClusterId, long fromVersionId,
                              long toClusterId, long toVersionId,
                              String reason, String reviewer) {
        AiNewsEventClusterLineageEntity lineage = new AiNewsEventClusterLineageEntity();
        lineage.setWorkspaceId(workspaceId);
        lineage.setOperationId(operationId);
        lineage.setOperationType(type);
        lineage.setFromClusterId(fromClusterId);
        lineage.setFromVersionId(fromVersionId);
        lineage.setToClusterId(toClusterId);
        lineage.setToVersionId(toVersionId);
        lineage.setReason(bounded(reason, 1000));
        lineage.setReviewer(bounded(reviewer, 256));
        lineage.setCreateTime(LocalDateTime.now());
        lineage.setDeleted(0);
        lineageMapper.insert(lineage);
    }

    private void supersedeReviews(long workspaceId, Collection<Long> clusterIds) {
        reviewMapper.update(null, new LambdaUpdateWrapper<AiNewsEventClusterReviewEntity>()
                .set(AiNewsEventClusterReviewEntity::getStatus, "SUPERSEDED")
                .set(AiNewsEventClusterReviewEntity::getResolvedAt, LocalDateTime.now())
                .set(AiNewsEventClusterReviewEntity::getUpdateTime, LocalDateTime.now())
                .eq(AiNewsEventClusterReviewEntity::getWorkspaceId, workspaceId)
                .eq(AiNewsEventClusterReviewEntity::getStatus, PENDING)
                .eq(AiNewsEventClusterReviewEntity::getDeleted, 0)
                .and(value -> value.in(AiNewsEventClusterReviewEntity::getSourceClusterId, clusterIds)
                        .or().in(AiNewsEventClusterReviewEntity::getCandidateClusterId, clusterIds)));
    }

    private static List<Long> normalizedIds(Collection<Long> values, int minimum, int maximum,
                                            String field) {
        List<Long> ids = values == null ? List.of() : values.stream()
                .filter(Objects::nonNull).filter(value -> value > 0)
                .distinct().sorted().toList();
        if (ids.size() < minimum || ids.size() > maximum) {
            throw new NewsClawException(400,
                    field + " 必须包含 " + minimum + " 到 " + maximum + " 个不同有效 ID");
        }
        return ids;
    }

    private static long requiredId(Long value, String field) {
        if (value == null || value <= 0) throw new NewsClawException(400, field + " 必须是有效 ID");
        return value;
    }

    private static String requiredActor(String value) {
        if (value == null || value.isBlank()) throw new NewsClawException(401, "未识别聚类复核操作者");
        return bounded(value, 256);
    }

    private static void requireEvent(AiNewsEventEntity event) {
        if (event == null || event.getId() == null || event.getWorkspaceId() == null
                || event.getWorkspaceId() <= 0 || nonzero(event.getDeleted())) {
            throw new IllegalArgumentException("persisted event is required for clustering");
        }
    }

    private static MemberSeed seed(AiNewsEventClusterMemberEntity member) {
        return new MemberSeed(member.getEventId(), number(member.getMembershipScore()),
                member.getAssignmentOrigin(), member.getScoreBreakdownJson(), member.getAssignedAt());
    }

    private static String manualBreakdown(String operation, String operationId) {
        return "{\"manualOperation\":\"" + operation + "\",\"operationId\":\""
                + operationId + "\"}";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("cannot serialize cluster provenance", error);
        }
    }

    private static long workspace(Long value) {
        return value == null || value <= 0 ? 1L : value;
    }

    private static double number(Double value) {
        return value == null || !Double.isFinite(value) ? 0.0D : value;
    }

    private static boolean nonzero(Integer value) {
        return value != null && value != 0;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second;
    }

    private static String suffix(String value) {
        return value == null || value.isBlank() ? "" : ": " + bounded(value, 900);
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum).trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private record Candidate(AiNewsEventClusterEntity cluster,
                             AiNewsEventClusterVersionEntity version,
                             AiNewsEventClusterScorer.Score score) {
    }

    private record MemberSeed(Long eventId, double score, String origin,
                              String breakdownJson, LocalDateTime assignedAt) {
    }

    private record VersionWrite(long clusterId, long versionId) {
    }

    public record Assignment(Long clusterId, Long clusterVersionId, String decision,
                             double score, boolean reviewRequired, Long reviewId) {
        static Assignment disabled() {
            return new Assignment(null, null, "DISABLED", 0.0D, false, null);
        }
    }

    public record BackfillResult(int considered, int assigned, int alreadyAssigned,
                                 int reviewSuggested) {
    }
}

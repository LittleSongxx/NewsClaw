package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import vip.newsclaw.audit.service.AuditEventService;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.content.model.ContentItemEntity;
import vip.newsclaw.content.repository.ContentItemMapper;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsCategory;
import vip.newsclaw.news.model.AiNewsEvidenceCaptureTrace;
import vip.newsclaw.news.model.AiNewsEvidenceRelation;
import vip.newsclaw.news.model.AiNewsEvidenceRequest;
import vip.newsclaw.news.model.AiNewsEventDetail;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEventStatus;
import vip.newsclaw.news.model.AiNewsEventUpsertRequest;
import vip.newsclaw.news.model.AiNewsSourceTier;
import vip.newsclaw.news.repository.AiNewsEvidenceMapper;
import vip.newsclaw.news.repository.AiNewsEventMapper;
import vip.newsclaw.team.model.TeamRunEntity;
import vip.newsclaw.team.repository.TeamRunMapper;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;
import vip.newsclaw.wiki.model.WikiPageEntity;
import vip.newsclaw.wiki.repository.WikiKnowledgeBaseMapper;
import vip.newsclaw.wiki.repository.WikiPageMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI news event state machine. Evidence is kept separate from the event so a
 * reviewer can inspect every source and the service can enforce a deterministic
 * official-first verification policy.
 */
@Slf4j
@Service
public class AiNewsEventService {

    private static final long DEFAULT_WORKSPACE = 1L;

    private final AiNewsEventMapper eventMapper;
    private final AiNewsEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;
    private final AiNewsSourceRegistry sourceRegistry;
    private final AiNewsDecisionPolicy decisionPolicy;
    private final AiNewsReviewRoutingService reviewRoutingService;

    @Value("${newsclaw.security.production-mode:false}")
    private boolean productionSecurity;

    @Autowired
    public AiNewsEventService(AiNewsEventMapper eventMapper,
                              AiNewsEvidenceMapper evidenceMapper,
                              ObjectMapper objectMapper,
                              AiNewsSourceRegistry sourceRegistry,
                              AiNewsReviewRoutingService reviewRoutingService) {
        this.eventMapper = eventMapper;
        this.evidenceMapper = evidenceMapper;
        this.objectMapper = objectMapper;
        this.sourceRegistry = sourceRegistry;
        this.decisionPolicy = new AiNewsDecisionPolicy(sourceRegistry);
        this.reviewRoutingService = reviewRoutingService;
    }

    /** Constructor kept for extension/test code that does not wire review routing. */
    public AiNewsEventService(AiNewsEventMapper eventMapper,
                              AiNewsEvidenceMapper evidenceMapper,
                              ObjectMapper objectMapper,
                              AiNewsSourceRegistry sourceRegistry) {
        this(eventMapper, evidenceMapper, objectMapper, sourceRegistry, null);
    }

    /** Narrow constructor retained for isolated policy tests. */
    public AiNewsEventService(AiNewsEventMapper eventMapper,
                              AiNewsEvidenceMapper evidenceMapper,
                              ObjectMapper objectMapper) {
        this(eventMapper, evidenceMapper, objectMapper, new AiNewsSourceRegistry(), null);
    }

    /*
     * These collaborators are optional on purpose.  The small service unit
     * tests (and migration tooling) can still construct the event service with
     * its original three-argument constructor, while the Spring application
     * gets cross-domain workspace checks for relation fields.
     */
    @Autowired(required = false)
    private TeamRunMapper teamRunMapper;

    @Autowired(required = false)
    private ContentItemMapper contentItemMapper;

    @Autowired(required = false)
    private WikiPageMapper wikiPageMapper;

    @Autowired(required = false)
    private WikiKnowledgeBaseMapper wikiKnowledgeBaseMapper;

    @Autowired(required = false)
    private AuditEventService auditEventService;

    @Autowired(required = false)
    private AiNewsCaptureAttemptService captureAttemptService;

    /** Optional for legacy extension tests; required when a request carries captureId. */
    @Autowired(required = false)
    private AiNewsSourceCaptureService sourceCaptureService;

    /** Optional for narrow tests; the Spring runtime wires the V210 cluster ledger. */
    @Autowired(required = false)
    private AiNewsEventClusterService eventClusterService;

    void setSourceCaptureService(AiNewsSourceCaptureService sourceCaptureService) {
        this.sourceCaptureService = sourceCaptureService;
    }

    void setEventClusterService(AiNewsEventClusterService eventClusterService) {
        this.eventClusterService = eventClusterService;
    }

    public IPage<AiNewsEventEntity> page(Long workspaceId, int page, int size,
                                         String category, String status, String keyword) {
        long ws = workspace(workspaceId);
        LambdaQueryWrapper<AiNewsEventEntity> query = baseEventQuery(ws);
        if (category != null && !category.isBlank()) {
            query.eq(AiNewsEventEntity::getCategory, category.trim().toLowerCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank()) {
            query.eq(AiNewsEventEntity::getStatus, status.trim().toLowerCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            query.and(q -> q.like(AiNewsEventEntity::getTitle, like)
                    .or().like(AiNewsEventEntity::getSummary, like)
                    .or().like(AiNewsEventEntity::getEntitiesJson, like));
        }
        query.orderByDesc(AiNewsEventEntity::getConfidence)
                .orderByDesc(AiNewsEventEntity::getRankingScore)
                .orderByDesc(AiNewsEventEntity::getSourcePublishedAt)
                .orderByDesc(AiNewsEventEntity::getPublishedAt)
                .orderByDesc(AiNewsEventEntity::getDiscoveredAt)
                .orderByDesc(AiNewsEventEntity::getCreateTime);
        IPage<AiNewsEventEntity> result = eventMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 100)), query);
        populateEvidenceSummary(ws, result.getRecords());
        populateReviewSummary(ws, result.getRecords());
        populateClusterSummary(ws, result.getRecords());
        return result;
    }

    public AiNewsEventDetail get(Long workspaceId, Long id) {
        AiNewsEventEntity event = findEvent(workspaceId, id);
        populateWikiNavigation(event);
        List<AiNewsEvidenceEntity> evidence = evidenceMapper.selectList(
                new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                        .eq(AiNewsEvidenceEntity::getWorkspaceId, workspace(workspaceId))
                        .eq(AiNewsEvidenceEntity::getEventId, id)
                        .eq(AiNewsEvidenceEntity::getDeleted, 0)
                        .orderByDesc(AiNewsEvidenceEntity::getSourceTier)
                        .orderByDesc(AiNewsEvidenceEntity::getSourcePublishedAt));
        populateReviewSummary(workspace(event.getWorkspaceId()), List.of(event));
        populateClusterSummary(workspace(event.getWorkspaceId()), List.of(event));
        return new AiNewsEventDetail(event, evidence,
                captureAttemptService == null ? List.of()
                        : captureAttemptService.list(workspace(event.getWorkspaceId()), id));
    }

    /**
     * Deterministic persisted-outcome summary for an Agent's frozen source
     * window. Tool errors and rejected writes are deliberately excluded: they
     * are execution telemetry, not durable event facts, and must not be guessed
     * by the model in its final answer.
     */
    public WindowSummary summarizeWindow(Long workspaceId, Instant windowStart, Instant windowEnd) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            throw new NewsClawException(400, "window_summary requires windowStart < windowEnd");
        }
        long ws = workspace(workspaceId);
        LocalDateTime start = LocalDateTime.ofInstant(windowStart, java.time.ZoneOffset.UTC);
        LocalDateTime end = LocalDateTime.ofInstant(windowEnd, java.time.ZoneOffset.UTC);
        List<AiNewsEventEntity> events = eventMapper.selectList(baseEventQuery(ws)
                .ge(AiNewsEventEntity::getSourcePublishedAt, start)
                .lt(AiNewsEventEntity::getSourcePublishedAt, end)
                .orderByDesc(AiNewsEventEntity::getRankingScore)
                .orderByDesc(AiNewsEventEntity::getSourcePublishedAt));
        if (events == null) events = List.of();
        populateReviewSummary(ws, events);
        populateClusterSummary(ws, events);
        List<Long> eventIds = events.stream().map(AiNewsEventEntity::getId)
                .filter(Objects::nonNull).toList();
        List<AiNewsEvidenceEntity> evidence = eventIds.isEmpty() ? List.of()
                : evidenceMapper.selectList(new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                        .eq(AiNewsEvidenceEntity::getWorkspaceId, ws)
                        .in(AiNewsEvidenceEntity::getEventId, eventIds)
                        .eq(AiNewsEvidenceEntity::getDeleted, 0));
        if (evidence == null) evidence = List.of();
        Map<Long, List<AiNewsEvidenceEntity>> byEvent = evidence.stream()
                .filter(item -> item.getEventId() != null)
                .collect(Collectors.groupingBy(AiNewsEvidenceEntity::getEventId));
        Map<String, Long> statuses = events.stream().collect(Collectors.groupingBy(
                item -> defaultToken(item.getStatus(), "unknown"), java.util.TreeMap::new,
                Collectors.counting()));
        Map<String, Long> categories = events.stream().collect(Collectors.groupingBy(
                item -> defaultToken(item.getCategory(), "unknown"), java.util.TreeMap::new,
                Collectors.counting()));
        Map<String, Long> reviewReasons = events.stream()
                .flatMap(item -> item.getReviewReasons() == null ? java.util.stream.Stream.empty()
                        : item.getReviewReasons().stream())
                .collect(Collectors.groupingBy(item -> defaultToken(item, "UNKNOWN"),
                        java.util.TreeMap::new, Collectors.counting()));
        long officialEvents = events.stream().filter(event -> byEvent.getOrDefault(event.getId(), List.of())
                .stream().anyMatch(item -> sourceRegistry.isOfficialUrl(
                        firstNonBlank(item.getFinalUrl(), item.getSourceUrl())))).count();
        long trustedMediaEvents = events.stream().filter(event -> byEvent.getOrDefault(event.getId(), List.of())
                .stream().anyMatch(item -> sourceRegistry.isTrustedMediaUrl(
                        firstNonBlank(item.getFinalUrl(), item.getSourceUrl())))).count();
        long attestedSupportEvents = events.stream().filter(event -> byEvent.getOrDefault(event.getId(), List.of())
                .stream().anyMatch(item -> AiNewsEvidenceRelation.from(item.getSemanticRelation()).supportsClaim()
                        && AiNewsRelationAttestation.isVerificationAttested(item.getRelationOrigin()))).count();
        long capturedEvidence = evidence.stream().filter(AiNewsEventService::captureBound).count();
        long verifiedEvents = events.stream().filter(item -> Set.of(
                        AiNewsEventStatus.VERIFIED.token(), AiNewsEventStatus.IN_PRODUCTION.token(),
                        AiNewsEventStatus.PUBLISHED.token()).contains(item.getStatus()))
                .count();
        long pendingReviewEvents = events.stream()
                .filter(item -> Boolean.TRUE.equals(item.getReviewRequired())).count();
        return new WindowSummary("server_persisted_window_summary", windowStart, windowEnd,
                events.size(), Map.copyOf(statuses), Map.copyOf(categories), evidence.size(),
                capturedEvidence, verifiedEvents, officialEvents, trustedMediaEvents,
                attestedSupportEvents, pendingReviewEvents, Map.copyOf(reviewReasons),
                "Counts only durable rows whose server-derived event.sourcePublishedAt is in the frozen window; event.publishedAt is reserved for editorial delivery. Tool failures and rejected writes require separate execution telemetry.");
    }

    /**
     * Populate a batch-loaded read projection for the event list. The
     * workbench reports verification from evidence rows, not from a downstream
     * event lifecycle state such as {@code in_production}.
     */
    private void populateEvidenceSummary(long workspaceId, List<AiNewsEventEntity> events) {
        if (events == null || events.isEmpty()) return;
        List<Long> eventIds = events.stream().map(AiNewsEventEntity::getId)
                .filter(Objects::nonNull).toList();
        if (eventIds.isEmpty()) return;
        Map<Long, List<AiNewsEvidenceEntity>> byEvent = evidenceMapper.selectList(
                        new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                                .eq(AiNewsEvidenceEntity::getWorkspaceId, workspaceId)
                                .in(AiNewsEvidenceEntity::getEventId, eventIds)
                                .eq(AiNewsEvidenceEntity::getDeleted, 0))
                .stream()
                .collect(Collectors.groupingBy(AiNewsEvidenceEntity::getEventId));
        for (AiNewsEventEntity event : events) {
            List<AiNewsEvidenceEntity> evidence = byEvent.getOrDefault(event.getId(), List.of());
            event.setEvidenceCount(evidence.size());
            event.setVerifiedEvidenceCount((int) evidence.stream()
                    .filter(item -> Boolean.TRUE.equals(item.getVerified())).count());
            event.setPrimaryEvidenceTier(evidence.stream()
                    .map(AiNewsEvidenceEntity::getSourceTier)
                    .filter(Objects::nonNull)
                    .min((left, right) -> Integer.compare(sourceTierRank(left), sourceTierRank(right)))
                    .orElse(null));
        }
    }

    private static int sourceTierRank(String tier) {
        if ("official".equalsIgnoreCase(tier)) return 0;
        if ("media".equalsIgnoreCase(tier)) return 1;
        if ("community".equalsIgnoreCase(tier)) return 2;
        return 3;
    }

    private void populateReviewSummary(long workspaceId, List<AiNewsEventEntity> events) {
        if (reviewRoutingService != null) {
            reviewRoutingService.populateProjection(workspaceId, events);
        }
    }

    private void populateClusterSummary(long workspaceId, List<AiNewsEventEntity> events) {
        if (eventClusterService != null) {
            eventClusterService.populateEventProjection(workspaceId, events);
        }
    }

    /**
     * Project the Wiki route target without duplicating Wiki ownership fields
     * in the news table. A stale/deleted page is deliberately left unresolved
     * so the UI can still show the stored page id without linking elsewhere.
     */
    private void populateWikiNavigation(AiNewsEventEntity event) {
        if (event == null || event.getWikiPageId() == null
                || wikiPageMapper == null || wikiKnowledgeBaseMapper == null) {
            return;
        }
        WikiPageEntity page = wikiPageMapper.selectById(event.getWikiPageId());
        if (page == null || (page.getDeleted() != null && page.getDeleted() != 0)) return;
        WikiKnowledgeBaseEntity kb = wikiKnowledgeBaseMapper.selectById(page.getKbId());
        if (kb == null || (kb.getDeleted() != null && kb.getDeleted() != 0)
                || !Objects.equals(workspace(event.getWorkspaceId()), workspace(kb.getWorkspaceId()))) {
            return;
        }
        event.setWikiKbId(page.getKbId());
        event.setWikiSlug(page.getSlug());
    }

    @Transactional
    public AiNewsEventEntity upsert(Long workspaceId, AiNewsEventUpsertRequest request) {
        long ws = workspace(workspaceId);
        List<AiNewsEvidenceRequest> evidence = request == null || request.evidence() == null
                ? List.of() : request.evidence();
        Map<CaptureBindingKey, AiNewsSourceCaptureService.BoundCapture> bindings =
                prepareCaptureBindings(ws, evidence, null, null, false);
        return upsertInternal(ws, request, bindings, false);
    }

    /**
     * Strict Agent entry point. Every evidence packet must reference a
     * server-owned source capture; the quoted text must be present in that
     * snapshot and its source publication time must fall inside the declared
     * discovery window.
     */
    @Transactional
    public AiNewsEventEntity upsertCaptured(Long workspaceId,
                                            AiNewsEventUpsertRequest request,
                                            Instant windowStart,
                                            Instant windowEnd) {
        if (windowStart == null || windowEnd == null) {
            throw new NewsClawException(400,
                    "Agent upsert 必须提供冻结的 windowStart/windowEnd 来源时间窗");
        }
        long ws = workspace(workspaceId);
        List<AiNewsEvidenceRequest> evidence = request == null || request.evidence() == null
                ? List.of() : request.evidence();
        Map<CaptureBindingKey, AiNewsSourceCaptureService.BoundCapture> bindings =
                prepareCaptureBindings(ws, evidence, windowStart, windowEnd, true);
        for (AiNewsSourceCaptureService.BoundCapture binding : bindings.values()) {
            AiNewsExplicitEventDateGuard.validate(binding.authoritativeQuote(),
                    binding.capture().getSourcePublishedAt(), windowStart, windowEnd);
        }
        return upsertInternal(ws, request, bindings, true);
    }

    /**
     * Attach one additional server-captured source to an existing event
     * without rewriting its title/claims.  This is used when two candidates
     * resolve to the same atomic fact; a verified event must retain its
     * lifecycle state while still gaining the corroborating provenance.
     */
    @Transactional
    public AiNewsEventEntity appendCapturedEvidence(Long workspaceId,
                                                    Long eventId,
                                                    AiNewsEvidenceRequest request,
                                                    Instant windowStart,
                                                    Instant windowEnd) {
        if (windowStart == null || windowEnd == null) {
            throw new NewsClawException(400,
                    "captured evidence 必须提供冻结的 windowStart/windowEnd 来源时间窗");
        }
        long ws = workspace(workspaceId);
        // Lock the event row before inspecting its lifecycle or appending a
        // source. The promotion bridge is also callable from multiple nodes;
        // a JVM synchronized block alone cannot serialize those writers.
        AiNewsEventEntity event = eventMapper.selectForUpdate(ws, eventId);
        if (event == null) event = findEvent(ws, eventId);
        if (AiNewsEventStatus.ARCHIVED.token().equalsIgnoreCase(event.getStatus())) {
            throw new NewsClawException(409, "已归档事件不能追加来源证据");
        }
        // Promotion is a candidate-to-event bridge, not a corroboration API
        // for an already verified/publication record.  Keeping the latter
        // immutable prevents a late or weak candidate quote from silently
        // changing the evidence set after editorial approval.
        if (!AiNewsEventStatus.CANDIDATE.token().equalsIgnoreCase(event.getStatus())
                && !AiNewsEventStatus.RESEARCHING.token().equalsIgnoreCase(event.getStatus())) {
            throw new NewsClawException(409,
                    "已核验或已发布事件不能通过 promotion 追加证据");
        }
        if (request == null || request.captureId() == null) {
            throw new NewsClawException(400, "追加证据必须绑定 captureId");
        }
        List<AiNewsEvidenceRequest> supplied = List.of(request);
        Map<CaptureBindingKey, AiNewsSourceCaptureService.BoundCapture> bindings =
                prepareCaptureBindings(ws, supplied, windowStart, windowEnd, true);
        for (AiNewsSourceCaptureService.BoundCapture binding : bindings.values()) {
            AiNewsExplicitEventDateGuard.validate(binding.authoritativeQuote(),
                    binding.capture().getSourcePublishedAt(), windowStart, windowEnd);
        }
        List<AiNewsEvidenceRequest> resolved = resolveCapturedEvidence(supplied, bindings);
        appendEvidence(event, ws, resolved, bindings);
        String previous = event.getStatus();
        boolean contradictory = resolved.stream().anyMatch(item ->
                AiNewsEvidenceRelation.CONTRADICTS.token().equalsIgnoreCase(item.semanticRelation()));
        if (contradictory && !AiNewsEventStatus.PUBLISHED.token().equals(event.getStatus())) {
            event.setStatus(AiNewsEventStatus.CONFLICTED.token());
            event.setConfidence(0.0D);
            event.setUpdateTime(LocalDateTime.now());
            eventMapper.updateById(event);
            audit("ai-news.event.status-changed", event,
                    Map.of("from", previous == null ? "" : previous,
                            "to", AiNewsEventStatus.CONFLICTED.token(),
                            "reason", "contradictory-promoted-evidence"));
        }
        refreshRankingScore(event, ws);
        assignCluster(event);
        audit("ai-news.evidence.captured", event,
                Map.of("sourceCaptureId", request.captureId(), "eventReuse", true));
        return synchronizeReviewTask(event);
    }

    private AiNewsEventEntity upsertInternal(
            long ws,
            AiNewsEventUpsertRequest request,
            Map<CaptureBindingKey, AiNewsSourceCaptureService.BoundCapture> captureBindings,
            boolean captureAuthoritative) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new NewsClawException(400, "AI 动态事件标题不能为空");
        }
        List<AiNewsEvidenceRequest> incomingEvidence = resolveCapturedEvidence(
                request.evidence() == null ? List.of() : request.evidence(), captureBindings);
        String sourceUrl = incomingEvidence.stream()
                .map(AiNewsEvidenceRequest::sourceUrl)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .findFirst()
                .orElse(null);
        if (sourceUrl == null && (request.eventKey() == null || request.eventKey().isBlank())) {
            throw new NewsClawException(400, "AI 动态事件必须提供来源 URL 或 eventKey");
        }
        String eventKey = normalizeKey(request.eventKey(), sourceUrl, request.title());
        AiNewsEventEntity event = eventMapper.selectOne(baseEventQuery(ws)
                .eq(AiNewsEventEntity::getEventKey, eventKey));
        if (event != null) {
            // Serialize packet writes per event across nodes.  The fallback is
            // needed by narrow extension tests that do not provide a locking
            // mapper result; production mappers return the locked row.
            AiNewsEventEntity locked = eventMapper.selectForUpdate(ws, event.getId());
            if (locked != null) event = locked;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean created = event == null;
        if (event == null) {
            event = new AiNewsEventEntity();
            event.setWorkspaceId(ws);
            event.setEventKey(eventKey);
            event.setStatus(AiNewsEventStatus.CANDIDATE.token());
            event.setConfidence(0.0D);
            event.setCreateTime(now);
            event.setDeleted(0);
        } else if (AiNewsEventStatus.PUBLISHED.token().equals(event.getStatus())) {
            // Published records are immutable except for additional evidence.
            appendEvidence(event, ws, incomingEvidence, captureBindings);
            refreshRankingScore(event, ws);
            assignCluster(event);
            return synchronizeReviewTask(event);
        } else if (AiNewsEventStatus.ARCHIVED.token().equals(event.getStatus())) {
            throw new NewsClawException(409, "已归档事件不能重新写入，请先恢复或创建新的事件");
        }
        event.setTitle(request.title().trim());
        event.setSummary(trimTo(request.summary(), 12000));
        event.setCategory(normalizeCategory(request.category()));
        event.setEntitiesJson(writeJson(request.entities() == null ? List.of() : request.entities()));
        event.setClaimsJson(writeJson(request.claims() == null ? List.of() : request.claims()));
        // A nullable conflicts field means "not supplied" on an update, not
        // "the conflict was resolved".  Clearing a persisted conflict is an
        // explicit operation (send an empty list), otherwise an evidence-only
        // retry could accidentally reopen an unsafe event.
        if (request.conflicts() != null || event.getId() == null) {
            event.setConflictsJson(writeJson(request.conflicts() == null ? List.of() : request.conflicts()));
        }
        event.setDiscoveredAt(request.discoveredAt() == null ? now : request.discoveredAt());
        LocalDateTime capturedPublishedAt = incomingEvidence.stream()
                .map(AiNewsEvidenceRequest::sourcePublishedAt)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
        // The strict Agent path must never let a caller override the timestamp
        // that the server parsed from the bound source snapshot. Manual REST
        // writes retain their historical compatibility behavior.
        if ((captureAuthoritative || !captureBindings.isEmpty()) && capturedPublishedAt != null
                && (event.getSourcePublishedAt() == null
                || capturedPublishedAt.isBefore(event.getSourcePublishedAt()))) {
            event.setSourcePublishedAt(capturedPublishedAt);
        } else if (!captureAuthoritative && captureBindings.isEmpty()
                && request.publishedAt() != null) {
            // Compatibility for the manual REST path. This timestamp belongs
            // to the editorial lifecycle and must never be reused as source time.
            event.setPublishedAt(request.publishedAt());
        }
        // Updating evidence or claims invalidates a prior verification.  A
        // reviewer must see the new packet before it can reach production.
        String previousStatus = event.getStatus();
        boolean hasIncomingConflicts = hasConflicts(event.getConflictsJson());
        if (event.getId() != null
                && !AiNewsEventStatus.CANDIDATE.token().equals(event.getStatus())
                && !AiNewsEventStatus.PUBLISHED.token().equals(event.getStatus())
                && (!incomingEvidence.isEmpty() || request.claims() != null || request.conflicts() != null)
                && !hasIncomingConflicts) {
            event.setStatus(AiNewsEventStatus.RESEARCHING.token());
            event.setConfidence(0.0D);
        }
        if (hasIncomingConflicts) {
            // A radar/核查 agent may already know that two claims disagree.
            // Persist that fact immediately so a transient retry cannot expose
            // the event as an apparently safe candidate for production.
            event.setStatus(AiNewsEventStatus.CONFLICTED.token());
            event.setConfidence(0.0D);
        }
        event.setUpdateTime(now);
        if (event.getId() == null) eventMapper.insert(event);
        else eventMapper.updateById(event);
        appendEvidence(event, ws, incomingEvidence, captureBindings);
        refreshRankingScore(event, ws);
        assignCluster(event);
        audit(created ? "ai-news.event.created" : "ai-news.event.updated",
                event, Map.of("status", event.getStatus(), "evidenceCount", incomingEvidence.size()));
        if (!Objects.equals(previousStatus, event.getStatus())) {
            audit("ai-news.event.status-changed", event,
                    Map.of("from", previousStatus == null ? "" : previousStatus,
                            "to", event.getStatus(), "reason", "evidence-or-claims-updated"));
        }
        return synchronizeReviewTask(event);
    }

    private void assignCluster(AiNewsEventEntity event) {
        if (eventClusterService == null) return;
        AiNewsEventClusterService.Assignment assignment = eventClusterService.assign(event);
        event.setClusterId(assignment.clusterId());
        event.setClusterVersionId(assignment.clusterVersionId());
        event.setClusterAssignmentOrigin(assignment.decision());
        event.setClusterAssignmentScore(assignment.score());
        event.setClusterReviewRequired(assignment.reviewRequired());
    }

    private Map<CaptureBindingKey, AiNewsSourceCaptureService.BoundCapture> prepareCaptureBindings(
            long workspaceId,
            List<AiNewsEvidenceRequest> evidence,
            Instant windowStart,
            Instant windowEnd,
            boolean requireAll) {
        if (requireAll && (evidence == null || evidence.isEmpty())) {
            throw new NewsClawException(400, "Agent upsert 至少需要一条带 captureId 的证据");
        }
        Map<CaptureBindingKey, AiNewsSourceCaptureService.BoundCapture> bindings = new LinkedHashMap<>();
        if (evidence == null) return bindings;
        for (AiNewsEvidenceRequest input : evidence) {
            Long captureId = input == null ? null : input.captureId();
            if (captureId == null) {
                if (requireAll) {
                    throw new NewsClawException(400,
                            "Agent upsert 不接受自由填写的 URL/quote；请先 capture_source 并提供 captureId");
                }
                continue;
            }
            if (sourceCaptureService == null) {
                throw new NewsClawException(503, "来源 capture 服务不可用");
            }
            AiNewsSourceCaptureService.BoundCapture bound = sourceCaptureService.bind(
                    workspaceId, captureId, input.quote(), windowStart, windowEnd);
            bindings.put(captureKey(captureId, input.quote()), bound);
        }
        return bindings;
    }

    private static List<AiNewsEvidenceRequest> resolveCapturedEvidence(
            List<AiNewsEvidenceRequest> supplied,
            Map<CaptureBindingKey, AiNewsSourceCaptureService.BoundCapture> bindings) {
        if (supplied == null || supplied.isEmpty()) return List.of();
        List<AiNewsEvidenceRequest> resolved = new ArrayList<>(supplied.size());
        for (AiNewsEvidenceRequest input : supplied) {
            if (input == null || input.captureId() == null) {
                resolved.add(input);
                continue;
            }
            AiNewsSourceCaptureService.BoundCapture bound = bindings.get(
                    captureKey(input.captureId(), input.quote()));
            if (bound == null) {
                throw new NewsClawException(409, "captureId 尚未通过 quote 绑定校验");
            }
            var capture = bound.capture();
            resolved.add(new AiNewsEvidenceRequest(
                    capture.getFinalUrl(), capture.getSourceTitle(), capture.getSourcePublishedAt(),
                    capture.getSourceTier(), input.claim(), bound.authoritativeQuote(), input.confidence(),
                    input.semanticRelation(), input.relationConfidence(), input.captureId()));
        }
        return List.copyOf(resolved);
    }

    @Transactional
    public AiNewsEventEntity verify(Long workspaceId, Long id, String verdict, Double confidence) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, id);
        if ("rejected".equalsIgnoreCase(verdict)) {
            ensureMutable(event);
            return transition(event, AiNewsEventStatus.REJECTED, confidence);
        }
        if ("conflicted".equalsIgnoreCase(verdict)) {
            ensureMutable(event);
            return transition(event, AiNewsEventStatus.CONFLICTED, confidence);
        }
        if (AiNewsEventStatus.VERIFIED.token().equals(event.getStatus())) {
            return synchronizeReviewTask(event);
        }
        ensureVerifiable(event);
        List<AiNewsEvidenceEntity> evidence = evidenceMapper.selectList(
                new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                        .eq(AiNewsEvidenceEntity::getWorkspaceId, workspace(event.getWorkspaceId()))
                        .eq(AiNewsEvidenceEntity::getEventId, event.getId())
                        .eq(AiNewsEvidenceEntity::getDeleted, 0));
        boolean highRisk = AiNewsRiskClassifier.isHighRisk(event, evidence);
        AiNewsDecisionPolicy.Decision decision = decisionPolicy.decideEntities(
                evidence, hasConflicts(event.getConflictsJson()), highRisk);
        if (!decision.verificationEligible()) {
            throw new NewsClawException(409, verificationFailure(decision));
        }
        boolean unattestedSupport = evidence.stream()
                .filter(item -> decision.supportingEvidenceIds().contains(
                        item.getId() == null ? "" : String.valueOf(item.getId())))
                .anyMatch(item -> !AiNewsRelationAttestation.isVerificationAttested(
                        item.getRelationOrigin()));
        if (unattestedSupport) {
            throw new NewsClawException(409,
                    "支持证据仍只有模型自报的 claim↔quote 关系；必须完成人工关系复核，"
                            + "或使用 claim 与 quote 完全相同的确定性摘录后才能核验");
        }
        for (AiNewsEvidenceEntity item : evidence) {
            String evidenceId = item.getId() == null ? "" : String.valueOf(item.getId());
            if (decision.supportingEvidenceIds().contains(evidenceId)
                    && !Boolean.TRUE.equals(item.getVerified())) {
                item.setVerified(true);
                item.setUpdateTime(LocalDateTime.now());
                evidenceMapper.updateById(item);
            }
        }
        return transition(event, AiNewsEventStatus.VERIFIED,
                confidence != null ? clamp(confidence) : decision.confidence());
    }

    /**
     * Persist an authenticated human judgment for one semantic relationship.
     * A content change later invalidates this attestation automatically.
     */
    @Transactional
    public AiNewsEvidenceEntity reviewEvidenceRelation(Long workspaceId,
                                                       Long eventId,
                                                       Long evidenceId,
                                                       String semanticRelation,
                                                       Double confidence,
                                                       String operator,
                                                       String note) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, eventId);
        ensureMutable(event);
        if (AiNewsEventStatus.IN_PRODUCTION.token().equals(event.getStatus())) {
            throw new NewsClawException(409, "事件已经进入内容生产，不能再修改证据语义关系");
        }
        AiNewsEvidenceEntity evidence = evidenceMapper.selectOne(
                new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                        .eq(AiNewsEvidenceEntity::getWorkspaceId, workspace(event.getWorkspaceId()))
                        .eq(AiNewsEvidenceEntity::getEventId, event.getId())
                        .eq(AiNewsEvidenceEntity::getId, evidenceId)
                        .eq(AiNewsEvidenceEntity::getDeleted, 0));
        if (evidence == null) throw new NewsClawException(404, "AI 动态证据不存在");
        AiNewsEvidenceRelation relation;
        try {
            relation = AiNewsEvidenceRelation.from(semanticRelation);
        } catch (IllegalArgumentException e) {
            throw new NewsClawException(400,
                    "semanticRelation 必须是 entails、contradicts、partial、unrelated 或 hedged");
        }
        if (relation == AiNewsEvidenceRelation.UNKNOWN) {
            throw new NewsClawException(400, "人工复核不能把语义关系标记为 unknown");
        }
        if (operator == null || operator.isBlank()) {
            throw new NewsClawException(401, "未识别证据复核操作者");
        }
        if (note == null || note.isBlank()) {
            throw new NewsClawException(400, "证据语义复核结论不能为空");
        }
        evidence.setSemanticRelation(relation.token());
        evidence.setRelationConfidence(clamp(confidence == null ? 1.0D : confidence));
        evidence.setRelationOrigin("HUMAN");
        evidence.setRelationReviewedAt(LocalDateTime.now());
        evidence.setRelationReviewedBy(trimTo(operator, 256));
        evidence.setRelationReviewNote(trimTo(note, 2000));
        evidence.setVerified(false);
        evidence.setUpdateTime(LocalDateTime.now());
        evidenceMapper.updateById(evidence);

        boolean rankingChanged = applyRankingScore(event, workspace(event.getWorkspaceId()));
        if (AiNewsEventStatus.VERIFIED.token().equals(event.getStatus())) {
            event.setStatus(AiNewsEventStatus.RESEARCHING.token());
            event.setConfidence(0.0D);
            event.setUpdateTime(LocalDateTime.now());
            eventMapper.updateById(event);
        } else if (rankingChanged) {
            event.setUpdateTime(LocalDateTime.now());
            eventMapper.updateById(event);
        }
        audit("ai-news.evidence.semantic-reviewed", event, Map.of(
                "evidenceId", evidence.getId(),
                "semanticRelation", relation.token(),
                "operator", operator.trim()));
        synchronizeReviewTask(event);
        return evidence;
    }

    @Transactional
    public AiNewsEventEntity dismiss(Long workspaceId, Long id) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, id);
        if (AiNewsEventStatus.REJECTED.token().equals(event.getStatus())) return synchronizeReviewTask(event);
        ensureMutable(event);
        return transition(event, AiNewsEventStatus.REJECTED, null);
    }

    /**
     * Record an operator's decision to keep researching a candidate. Conflicted
     * events deliberately remain conflicted until their claims are corrected;
     * the action is still audited and is idempotent for repeated card clicks.
     */
    @Transactional
    public AiNewsEventEntity continueResearch(Long workspaceId, Long id) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, id);
        ensureMutable(event);
        if (AiNewsEventStatus.CANDIDATE.token().equals(event.getStatus())) {
            AiNewsEventEntity researching = transition(event, AiNewsEventStatus.RESEARCHING, 0.0D);
            audit("ai-news.event.follow-up-requested", researching, Map.of("source", "operator"));
            return researching;
        }
        if (AiNewsEventStatus.RESEARCHING.token().equals(event.getStatus())
                || AiNewsEventStatus.CONFLICTED.token().equals(event.getStatus())) {
            audit("ai-news.event.follow-up-requested", event, Map.of("source", "operator"));
            return synchronizeReviewTask(event);
        }
        throw new NewsClawException(409, "当前事件状态不需要继续跟踪");
    }

    @Transactional
    public AiNewsEventEntity beginProduction(Long workspaceId, Long id) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, id);
        if (AiNewsEventStatus.IN_PRODUCTION.token().equals(event.getStatus())) return synchronizeReviewTask(event);
        if (!AiNewsEventStatus.VERIFIED.token().equals(event.getStatus())) {
            throw new NewsClawException(409, "只有已核验事件才能开始内容生产");
        }
        if (reviewRoutingService != null) {
            reviewRoutingService.requireClearForProduction(event);
        }
        return transition(event, AiNewsEventStatus.IN_PRODUCTION, event.getConfidence());
    }

    /**
     * Close the delivery leg after an operator has attached at least one
     * channel artifact (公众号 draft or 小红书 material package).  The
     * platform APIs are intentionally outside this domain; this explicit
     * callback records the operator's delivery acknowledgement and is
     * idempotent for retries from IM/webhook handlers.
     */
    @Transactional
    public AiNewsEventEntity markPublished(Long workspaceId, Long id) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, id);
        if (AiNewsEventStatus.PUBLISHED.token().equals(event.getStatus())) return synchronizeReviewTask(event);
        ensureMutable(event);
        if (!AiNewsEventStatus.IN_PRODUCTION.token().equals(event.getStatus())) {
            throw new NewsClawException(409, "只有进入内容生产的事件才能标记已交付");
        }
        if (!hasActiveDeliveryArtifact(event)) {
            throw new NewsClawException(409,
                    "只有具备人工审核记录、工件哈希和非空平台回执的内容条目才能标记平台已发布");
        }
        LocalDateTime publishedAt = LocalDateTime.now();
        event.setPublishedAt(publishedAt);
        event.setPlatformPublishedAt(publishedAt);
        event.setDeliveryStatus("platform_published");
        ContentItemEntity delivery = contentItemMapper == null ? null : firstPlatformArtifact(event);
        if (delivery != null) {
            event.setPlatformExternalRef(delivery.getExternalRef());
            event.setArtifactHash(delivery.getArtifactHash());
        }
        AiNewsEventEntity published = transition(event, AiNewsEventStatus.PUBLISHED, event.getConfidence());
        audit("ai-news.event.published", published,
                Map.of("gzhContentItemId", published.getGzhContentItemId() == null ? 0L : published.getGzhContentItemId(),
                        "xhsContentItemId", published.getXhsContentItemId() == null ? 0L : published.getXhsContentItemId()));
        return published;
    }

    /** Record operator approval separately from a platform publication ACK. */
    @Transactional
    public AiNewsEventEntity acknowledgeDelivery(Long workspaceId, Long id, String artifactHash) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, id);
        ensureMutable(event);
        if (!AiNewsEventStatus.IN_PRODUCTION.token().equals(event.getStatus())) {
            throw new NewsClawException(409, "只有内容生产中的事件才能记录人工交付确认");
        }
        if (artifactHash == null || !artifactHash.trim().matches("(?i)[0-9a-f]{64}")) {
            throw new NewsClawException(400, "artifactHash 必须是 64 位 SHA-256");
        }
        if (contentItemMapper != null) {
            ContentItemEntity linked = linkedContent(event);
            if (linked == null || linked.getArtifactHash() == null
                    || !artifactHash.trim().equalsIgnoreCase(linked.getArtifactHash())) {
                throw new NewsClawException(409, "artifactHash 必须与已关联内容条目的人工审核工件一致");
            }
        } else if (productionSecurity) {
            throw new NewsClawException(503, "内容交付台账不可用，拒绝记录生产确认");
        }
        event.setDeliveryStatus("operator_acknowledged");
        event.setArtifactHash(artifactHash.trim().toLowerCase(Locale.ROOT));
        event.setOperatorAcknowledgedAt(LocalDateTime.now());
        event.setUpdateTime(LocalDateTime.now());
        eventMapper.updateById(event);
        audit("ai-news.delivery.operator-acknowledged", event,
                Map.of("artifactHash", event.getArtifactHash()));
        return synchronizeReviewTask(event);
    }

    @Transactional
    public AiNewsEventEntity linkRun(Long workspaceId, Long id, Long runId) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, id);
        if (runId == null) throw new NewsClawException(400, "team run id 不能为空");
        if (teamRunMapper != null) {
            TeamRunEntity run = teamRunMapper.selectById(runId);
            if (run == null || !Objects.equals(workspace(event.getWorkspaceId()), run.getWorkspaceId())
                    || (run.getDeleted() != null && run.getDeleted() != 0)) {
                throw new NewsClawException(409, "Team Run 不属于当前 workspace");
            }
        }
        event.setTeamRunId(runId);
        event.setUpdateTime(LocalDateTime.now());
        eventMapper.updateById(event);
        audit("ai-news.event.run-linked", event, Map.of("teamRunId", runId));
        return synchronizeReviewTask(event);
    }

    @Transactional
    public AiNewsEventEntity linkContent(Long workspaceId, Long id, Long contentId, String platform) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, id);
        if (contentId == null) throw new NewsClawException(400, "content item id 不能为空");
        String normalizedPlatform = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("gzh", "xhs").contains(normalizedPlatform)) {
            throw new NewsClawException(400, "platform 必须是 gzh 或 xhs");
        }
        if (contentItemMapper != null) {
            ContentItemEntity content = contentItemMapper.selectOne(new LambdaQueryWrapper<ContentItemEntity>()
                    .eq(ContentItemEntity::getId, contentId)
                    .eq(ContentItemEntity::getWorkspaceId, workspace(event.getWorkspaceId()))
                    .eq(ContentItemEntity::getDeleted, 0));
            if (content == null) {
                throw new NewsClawException(409, "内容条目不属于当前 workspace");
            }
            if (content.getPlatform() != null && !normalizedPlatform.equalsIgnoreCase(content.getPlatform())) {
                throw new NewsClawException(409, "内容条目平台与关联平台不一致");
            }
        }
        if ("gzh".equals(normalizedPlatform)) event.setGzhContentItemId(contentId);
        else event.setXhsContentItemId(contentId);
        event.setUpdateTime(LocalDateTime.now());
        eventMapper.updateById(event);
        audit("ai-news.event.content-linked", event,
                Map.of("contentId", contentId, "platform", platform.toLowerCase(Locale.ROOT)));
        return synchronizeReviewTask(event);
    }

    /**
     * Attach the internal Wiki evidence page after the evidence packet has been
     * archived.  A page is accepted only when both the page and its knowledge
     * base belong to the selected workspace, preventing a cross-workspace
     * reference from becoming visible in the event workbench.
     */
    @Transactional
    public AiNewsEventEntity linkWiki(Long workspaceId, Long id, Long wikiPageId) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, id);
        if (wikiPageId == null) throw new NewsClawException(400, "wiki page id 不能为空");
        if (wikiPageMapper != null && wikiKnowledgeBaseMapper != null) {
            WikiPageEntity page = wikiPageMapper.selectById(wikiPageId);
            WikiKnowledgeBaseEntity kb = page == null ? null : wikiKnowledgeBaseMapper.selectById(page.getKbId());
            if (page == null || kb == null
                    || (page.getDeleted() != null && page.getDeleted() != 0)
                    || (kb.getDeleted() != null && kb.getDeleted() != 0)
                    || !Objects.equals(workspace(event.getWorkspaceId()), workspace(kb.getWorkspaceId()))) {
                throw new NewsClawException(409, "Wiki 证据页不属于当前 workspace");
            }
        }
        event.setWikiPageId(wikiPageId);
        populateWikiNavigation(event);
        event.setUpdateTime(LocalDateTime.now());
        eventMapper.updateById(event);
        audit("ai-news.event.wiki-linked", event, Map.of("wikiPageId", wikiPageId));
        return synchronizeReviewTask(event);
    }

    @Transactional
    public AiNewsEventEntity archive(Long workspaceId, Long id) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, id);
        if (AiNewsEventStatus.ARCHIVED.token().equals(event.getStatus())) return synchronizeReviewTask(event);
        // Published events remain part of the editorial history and may be
        // archived after delivery without reopening verification.
        return transition(event, AiNewsEventStatus.ARCHIVED, null);
    }

    public AiNewsEventEntity findEvent(Long workspaceId, Long id) {
        AiNewsEventEntity event = eventMapper.selectOne(baseEventQuery(workspace(workspaceId))
                .eq(AiNewsEventEntity::getId, id));
        if (event == null) throw new NewsClawException(404, "AI 动态事件不存在");
        return event;
    }

    /** Serialize every lifecycle/evidence mutation on the authoritative event row. */
    AiNewsEventEntity findEventForUpdate(Long workspaceId, Long id) {
        long ws = workspace(workspaceId);
        AiNewsEventEntity event = eventMapper.selectForUpdate(ws, id);
        // Narrow unit tests and legacy mapper doubles may not implement the lock query.
        return event != null ? event : findEvent(ws, id);
    }

    /** Resolve a workspace event by the same normalized key used by upsert. */
    public AiNewsEventEntity findEventByKey(Long workspaceId, String eventKey) {
        if (eventKey == null || eventKey.isBlank()) return null;
        return eventMapper.selectOne(baseEventQuery(workspace(workspaceId))
                .eq(AiNewsEventEntity::getEventKey, normalizeKey(eventKey, null, null)));
    }

    /**
     * Deterministic workflow delivery gate.  A model response containing an
     * event id is not proof that the event is publishable; the authoritative
     * lifecycle row must already be verified (or further along) and retain at
     * least one evidence row in the same workspace.
     */
    public boolean isVerifiedForPublication(Long workspaceId, Long eventId) {
        if (eventId == null || eventId <= 0) return false;
        AiNewsEventEntity event;
        try {
            event = findEvent(workspaceId, eventId);
        } catch (Exception ignored) {
            return false;
        }
        String status = event.getStatus();
        boolean lifecycleReady = AiNewsEventStatus.VERIFIED.token().equalsIgnoreCase(status)
                || AiNewsEventStatus.IN_PRODUCTION.token().equalsIgnoreCase(status)
                || AiNewsEventStatus.PUBLISHED.token().equalsIgnoreCase(status);
        if (!lifecycleReady) return false;
        Long evidenceCount = evidenceMapper.selectCount(new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                .eq(AiNewsEvidenceEntity::getWorkspaceId, workspace(event.getWorkspaceId()))
                .eq(AiNewsEvidenceEntity::getEventId, eventId)
                .eq(AiNewsEvidenceEntity::getDeleted, 0));
        return evidenceCount != null && evidenceCount > 0;
    }

    /**
     * Attach a packet captured through the narrow official-source read-only
     * boundary. This persists capture provenance but intentionally does not
     * call {@link #verify(Long, Long, String, Double)}: an operator/agent must
     * still judge the claim against all evidence.
     */
    @Transactional
    public AiNewsEvidenceEntity attachCapturedOfficialEvidence(Long workspaceId, Long eventId,
                                                                 AiNewsEvidenceRequest request,
                                                                 AiNewsEvidenceCaptureTrace trace) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, eventId);
        ensureMutable(event);
        if (request == null || !"official".equalsIgnoreCase(request.sourceTier())
                || !sourceRegistry.isOfficialUrl(request.sourceUrl())) {
            throw new NewsClawException(400, "只读抓取只能归档官方来源证据");
        }
        if (trace == null || trace.finalUrl() == null || !sourceRegistry.isOfficialUrl(trace.finalUrl())) {
            throw new NewsClawException(400, "官方来源重定向后的 URL 不合法");
        }
        List<AiNewsEvidenceEntity> written = appendEvidence(event, workspace(event.getWorkspaceId()), List.of(request));
        AiNewsEvidenceEntity evidence = written.getFirst();
        evidence.setFinalUrl(trimTo(trace.finalUrl(), 4096));
        evidence.setFetchedAt(trace.fetchedAt());
        evidence.setContentHash(trimTo(trace.contentHash(), 64));
        evidence.setHttpStatus(trace.httpStatus());
        evidence.setCaptureMethod(trimTo(trace.captureMethod(), 32));
        evidence.setRedirectChainJson(trimTo(trace.redirectChainJson(), 12000));
        evidence.setUpdateTime(LocalDateTime.now());
        evidenceMapper.updateById(evidence);
        if (AiNewsEventStatus.VERIFIED.token().equals(event.getStatus())) {
            event.setStatus(AiNewsEventStatus.RESEARCHING.token());
            event.setConfidence(0.0D);
            event.setUpdateTime(LocalDateTime.now());
            eventMapper.updateById(event);
            audit("ai-news.event.status-changed", event,
                    Map.of("from", AiNewsEventStatus.VERIFIED.token(),
                            "to", AiNewsEventStatus.RESEARCHING.token(),
                            "reason", "official-evidence-captured"));
        }
        audit("ai-news.evidence.captured", event, Map.of(
                "evidenceId", evidence.getId() == null ? 0L : evidence.getId(),
                "httpStatus", trace.httpStatus() == null ? 0 : trace.httpStatus(),
                "captureMethod", trace.captureMethod() == null ? "" : trace.captureMethod()));
        refreshRankingScore(event, workspace(event.getWorkspaceId()));
        synchronizeReviewTask(event);
        return evidence;
    }

    private void refreshRankingScore(AiNewsEventEntity event, long workspaceId) {
        if (applyRankingScore(event, workspaceId)) {
            event.setUpdateTime(LocalDateTime.now());
            eventMapper.updateById(event);
        }
    }

    private boolean applyRankingScore(AiNewsEventEntity event, long workspaceId) {
        if (event == null || event.getId() == null) return false;
        List<AiNewsEvidenceEntity> evidence = evidenceMapper.selectList(
                new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                        .eq(AiNewsEvidenceEntity::getWorkspaceId, workspaceId)
                        .eq(AiNewsEvidenceEntity::getEventId, event.getId())
                        .eq(AiNewsEvidenceEntity::getDeleted, 0));
        double score = AiNewsEventRankingPolicy.score(event,
                evidence == null ? List.of() : evidence, sourceRegistry);
        double current = event.getRankingScore() == null ? 0.0D : event.getRankingScore();
        if (Double.compare(current, score) != 0) {
            event.setRankingScore(score);
            return true;
        }
        return false;
    }

    private List<AiNewsEvidenceEntity> appendEvidence(AiNewsEventEntity event, long workspaceId,
                                                       Collection<AiNewsEvidenceRequest> inputs) {
        return appendEvidence(event, workspaceId, inputs, Map.of());
    }

    private List<AiNewsEvidenceEntity> appendEvidence(
            AiNewsEventEntity event,
            long workspaceId,
            Collection<AiNewsEvidenceRequest> inputs,
            Map<CaptureBindingKey, AiNewsSourceCaptureService.BoundCapture> captureBindings) {
        List<AiNewsEvidenceEntity> written = new ArrayList<>();
        for (AiNewsEvidenceRequest input : inputs) {
            if (input == null || input.sourceUrl() == null || input.sourceUrl().isBlank()) {
                throw new NewsClawException(400, "证据 sourceUrl 不能为空");
            }
            if (!isValidSourceUrl(input.sourceUrl())) {
                throw new NewsClawException(400, "证据 sourceUrl 必须是有效的 http/https URL");
            }
            if (input.claim() == null || input.claim().isBlank()) {
                throw new NewsClawException(400, "证据 claim 不能为空");
            }
            AiNewsSourceTier tier;
            try {
                tier = AiNewsSourceTier.from(input.sourceTier());
            } catch (IllegalArgumentException invalidTier) {
                throw new NewsClawException(400, "证据 sourceTier 必须是 official、media 或 community");
            }
            String url = canonicalUrl(input.sourceUrl());
            String urlHash = sha256(url);
            String claim = trimTo(input.claim(), 12000);
            String quote = trimTo(input.quote(), 12000);
            String identityHash = evidenceIdentityHash(url, claim, quote, input.captureId());
            // Find the exact packet first.  The URL-only lookup is retained
            // solely for legacy rows created before V218; it must never make a
            // different claim/quote overwrite that row.
            AiNewsEvidenceEntity existing = evidenceMapper.selectOne(
                    new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                            .eq(AiNewsEvidenceEntity::getEventId, event.getId())
                            .eq(AiNewsEvidenceEntity::getWorkspaceId, workspaceId)
                            .eq(AiNewsEvidenceEntity::getEvidenceIdentityHash, identityHash));
            if (existing == null) {
                List<AiNewsEvidenceEntity> legacyRows = evidenceMapper.selectList(
                        new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                                .eq(AiNewsEvidenceEntity::getEventId, event.getId())
                                .eq(AiNewsEvidenceEntity::getWorkspaceId, workspaceId)
                                .and(q -> q.eq(AiNewsEvidenceEntity::getSourceUrlHash, urlHash)
                                        .or().eq(AiNewsEvidenceEntity::getSourceUrl, url))
                                .eq(AiNewsEvidenceEntity::getDeleted, 0));
                if (legacyRows != null) {
                    existing = legacyRows.stream()
                            .filter(row -> row != null && row.getEvidenceIdentityHash() == null
                                    && sameEvidenceIdentity(row, url, claim, quote, input.captureId()))
                            .findFirst().orElse(null);
                }
            }
            AiNewsSourceCaptureService.BoundCapture captureBinding = input.captureId() == null
                    ? null : captureBindings.get(captureKey(input.captureId(), input.quote()));
            if (input.captureId() != null && captureBinding == null) {
                throw new NewsClawException(409, "captureId 尚未通过服务端绑定校验");
            }
            if (existing != null && existing.getSourceCaptureId() != null && captureBinding == null) {
                throw new NewsClawException(409,
                        "已绑定 capture 的证据不能被自由填写的 URL/quote 覆盖");
            }
            AiNewsEvidenceEntity row = existing == null ? new AiNewsEvidenceEntity() : existing;
            AiNewsEvidenceRelation requestedRelation;
            try {
                requestedRelation = AiNewsEvidenceRelation.from(input.semanticRelation());
            } catch (IllegalArgumentException invalidRelation) {
                throw new NewsClawException(400,
                        "证据 semanticRelation 必须是 entails、contradicts、partial、unrelated、hedged 或 unknown");
            }
            boolean assessmentSupplied = input.semanticRelation() != null
                    && !input.semanticRelation().isBlank();
            boolean deterministicExtractive = AiNewsRelationAttestation
                    .isExactExtractiveEntailment(input.claim(), input.quote(), requestedRelation);
            boolean contentChanged = existing == null
                    || !Objects.equals(existing.getClaim(), input.claim().trim())
                    || !Objects.equals(existing.getSourceTier(), tier.token())
                    || !Objects.equals(existing.getQuote(), trimTo(input.quote(), 12000))
                    || !Objects.equals(existing.getSourceCaptureId(), input.captureId());
            boolean modelAssessmentMayUpdate = existing == null || contentChanged
                    || !"HUMAN".equalsIgnoreCase(existing.getRelationOrigin());
            boolean relationChanged = assessmentSupplied && modelAssessmentMayUpdate
                    && (existing == null
                    || !Objects.equals(existing.getSemanticRelation(), requestedRelation.token())
                    || !Objects.equals(existing.getRelationConfidence(),
                    clamp(input.relationConfidence() == null ? 0.0D : input.relationConfidence())));
            boolean changed = contentChanged || relationChanged;
            row.setEventId(event.getId());
            row.setWorkspaceId(workspaceId);
            row.setSourceUrl(url);
            row.setSourceUrlHash(urlHash);
            row.setEvidenceIdentityHash(identityHash);
            row.setSourceTitle(trimTo(input.sourceTitle(), 512));
            row.setSourcePublishedAt(input.sourcePublishedAt());
            row.setSourceTier(tier.token());
            row.setClaim(claim);
            row.setQuote(quote);
            row.setConfidence(clamp(input.confidence() == null ? 0.0D : input.confidence()));
            if (captureBinding != null) {
                applyCaptureBinding(row, captureBinding);
            } else if (contentChanged) {
                clearCaptureBinding(row);
            }
            if (existing == null || contentChanged) {
                row.setSemanticRelation(requestedRelation.token());
                row.setRelationConfidence(requestedRelation == AiNewsEvidenceRelation.UNKNOWN
                        ? null : deterministicExtractive ? 1.0D
                        : clamp(input.relationConfidence() == null ? 0.0D : input.relationConfidence()));
                row.setRelationOrigin(requestedRelation == AiNewsEvidenceRelation.UNKNOWN
                        ? AiNewsRelationAttestation.UNKNOWN
                        : deterministicExtractive ? AiNewsRelationAttestation.DETERMINISTIC_EXTRACTIVE
                        : AiNewsRelationAttestation.MODEL);
                row.setRelationReviewedAt(null);
                row.setRelationReviewedBy(null);
                row.setRelationReviewNote(null);
            } else if (assessmentSupplied && modelAssessmentMayUpdate) {
                row.setSemanticRelation(requestedRelation.token());
                row.setRelationConfidence(requestedRelation == AiNewsEvidenceRelation.UNKNOWN
                        ? null : deterministicExtractive ? 1.0D
                        : clamp(input.relationConfidence() == null ? 0.0D : input.relationConfidence()));
                row.setRelationOrigin(requestedRelation == AiNewsEvidenceRelation.UNKNOWN
                        ? AiNewsRelationAttestation.UNKNOWN
                        : deterministicExtractive ? AiNewsRelationAttestation.DETERMINISTIC_EXTRACTIVE
                        : AiNewsRelationAttestation.MODEL);
            }
            if (row.getVerified() == null || changed) row.setVerified(false);
            row.setDeleted(0);
            row.setUpdateTime(LocalDateTime.now());
            if (row.getCreateTime() == null) row.setCreateTime(LocalDateTime.now());
            if (row.getId() == null) evidenceMapper.insert(row); else evidenceMapper.updateById(row);
            written.add(row);
        }
        return written;
    }

    private static void applyCaptureBinding(
            AiNewsEvidenceEntity row,
            AiNewsSourceCaptureService.BoundCapture binding) {
        var capture = binding.capture();
        row.setSourceCaptureId(capture.getId());
        row.setQuoteStart(binding.quoteStart());
        row.setQuoteEnd(binding.quoteEnd());
        row.setQuoteMatchMethod(binding.quoteMatchMethod());
        row.setFinalUrl(trimTo(capture.getFinalUrl(), 4096));
        row.setFetchedAt(capture.getFetchedAt());
        row.setContentHash(trimTo(capture.getContentHash(), 64));
        row.setHttpStatus(capture.getHttpStatus());
        row.setCaptureMethod(trimTo(capture.getCaptureMethod(), 32));
        row.setRedirectChainJson(trimTo(capture.getRedirectChainJson(), 12000));
    }

    private static void clearCaptureBinding(AiNewsEvidenceEntity row) {
        row.setSourceCaptureId(null);
        row.setQuoteStart(null);
        row.setQuoteEnd(null);
        row.setQuoteMatchMethod(null);
        row.setFinalUrl(null);
        row.setFetchedAt(null);
        row.setContentHash(null);
        row.setHttpStatus(null);
        row.setCaptureMethod(null);
        row.setRedirectChainJson(null);
    }

    private static CaptureBindingKey captureKey(Long captureId, String quote) {
        return new CaptureBindingKey(captureId, AiNewsSourceDocumentParser.normalizeText(quote));
    }

    private record CaptureBindingKey(Long captureId, String normalizedQuote) {
    }

    private AiNewsEventEntity transition(AiNewsEventEntity event, AiNewsEventStatus status, Double confidence) {
        String previous = event.getStatus();
        event.setStatus(status.token());
        if (confidence != null) event.setConfidence(clamp(confidence));
        event.setUpdateTime(LocalDateTime.now());
        eventMapper.updateById(event);
        if (!Objects.equals(previous, status.token())) {
            audit("ai-news.event.status-changed", event,
                    Map.of("from", previous == null ? "" : previous, "to", status.token(),
                            "confidence", event.getConfidence() == null ? 0.0D : event.getConfidence()));
        }
        return synchronizeReviewTask(event);
    }

    private AiNewsEventEntity synchronizeReviewTask(AiNewsEventEntity event) {
        if (reviewRoutingService != null) reviewRoutingService.sync(event);
        return event;
    }

    private static String verificationFailure(AiNewsDecisionPolicy.Decision decision) {
        if (decision.reasons().contains(AiNewsDecisionPolicy.Reason.NO_EVIDENCE)) {
            return "至少需要一条来源证据";
        }
        if (decision.reasons().contains(AiNewsDecisionPolicy.Reason.MISSING_CAPTURE_PROVENANCE)) {
            return "可信来源尚未通过只读 capture 绑定 URL 与原文引文，不能核验";
        }
        if (decision.reasons().contains(AiNewsDecisionPolicy.Reason.MISSING_SOURCE_TIMESTAMP)) {
            return "可信来源缺少可核验的来源发布时间，不能作为最新新闻核验";
        }
        if (decision.unresolvedConflict()) {
            return "事件存在未解决的可信来源冲突，请先修正 claims 或完成人工复核";
        }
        if (decision.reasons().contains(AiNewsDecisionPolicy.Reason.MISSING_SEMANTIC_ASSESSMENT)) {
            return "可信来源证据尚未完成 claim↔quote 语义关系判定，不能核验";
        }
        if (decision.highRisk()) {
            return "安全、合规等高风险声明必须有注册官方/原始来源的直接支持";
        }
        if ("community".equals(decision.sourceTier())) {
            return "关键事件需要一个注册官方来源，或两个来源注册表中的独立可信媒体；未注册来源只能作为线索";
        }
        if (!decision.claimQuoteSupported()) {
            return "现有可信来源引文没有直接支持事件声明";
        }
        return "关键事件需要一个直接支持声明的注册官方来源，或两个独立可信媒体的直接支持";
    }

    private LambdaQueryWrapper<AiNewsEventEntity> baseEventQuery(long workspaceId) {
        return new LambdaQueryWrapper<AiNewsEventEntity>()
                .eq(AiNewsEventEntity::getWorkspaceId, workspaceId)
                .eq(AiNewsEventEntity::getDeleted, 0);
    }

    private static long workspace(Long id) {
        return id == null || id <= 0 ? DEFAULT_WORKSPACE : id;
    }

    private static String normalizeCategory(String category) {
        try {
            return AiNewsCategory.normalize(category);
        } catch (IllegalArgumentException e) {
            throw new NewsClawException(400, e.getMessage());
        }
    }

    private static String normalizeKey(String supplied, String sourceUrl, String title) {
        String raw = supplied == null || supplied.isBlank()
                ? (sourceUrl == null || sourceUrl.isBlank() ? title : canonicalUrl(sourceUrl))
                : supplied.trim();
        if (raw.startsWith("http://") || raw.startsWith("https://")
                || raw.startsWith("HTTP://") || raw.startsWith("HTTPS://")) {
            raw = canonicalUrl(raw);
        }
        return sha256(raw);
    }

    private static boolean isValidSourceUrl(String value) {
        try {
            URI uri = new URI(value.trim());
            return ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static void ensureMutable(AiNewsEventEntity event) {
        String status = event.getStatus();
        if (AiNewsEventStatus.PUBLISHED.token().equals(status)
                || AiNewsEventStatus.ARCHIVED.token().equals(status)) {
            throw new NewsClawException(409, "事件已交付或归档，不能再修改核验状态");
        }
    }

    private static void ensureVerifiable(AiNewsEventEntity event) {
        ensureMutable(event);
        if (AiNewsEventStatus.IN_PRODUCTION.token().equals(event.getStatus())) {
            throw new NewsClawException(409, "事件已经进入内容生产");
        }
    }

    public static String canonicalUrl(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isBlank()) return "";
        try {
            URI uri = new URI(input);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/{2,}", "/");
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = canonicalQuery(uri.getRawQuery());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            String authority = host + (port > 0 ? ":" + port : "");
            return scheme + "://" + authority + path + query;
        } catch (URISyntaxException e) {
            return input.split("#", 2)[0];
        }
    }

    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return "";
        List<String> kept = new ArrayList<>();
        for (String parameter : rawQuery.split("&")) {
            String name = parameter.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (name.startsWith("utm_") || Set.of("fbclid", "gclid", "mc_cid", "mc_eid")
                    .contains(name)) continue;
            if (!parameter.isBlank()) kept.add(parameter);
        }
        return kept.isEmpty() ? "" : "?" + String.join("&", kept);
    }

    /**
     * Delivery acknowledgement must point at a live content-calendar row. The
     * fallback for mapper-less unit construction preserves the service's
     * small pure-state tests while the Spring path enforces workspace and
     * soft-delete isolation.
     */
    private boolean hasActiveDeliveryArtifact(AiNewsEventEntity event) {
        if (contentItemMapper == null) {
            if (productionSecurity) return false;
            return event.getGzhContentItemId() != null || event.getXhsContentItemId() != null;
        }
        return firstPlatformArtifact(event) != null;
    }

    private ContentItemEntity firstPlatformArtifact(AiNewsEventEntity event) {
        long ws = workspace(event.getWorkspaceId());
        ContentItemEntity gzh = findPlatformArtifact(event.getGzhContentItemId(), ws, "gzh");
        return gzh != null ? gzh : findPlatformArtifact(event.getXhsContentItemId(), ws, "xhs");
    }

    private ContentItemEntity linkedContent(AiNewsEventEntity event) {
        long ws = workspace(event.getWorkspaceId());
        ContentItemEntity content = findAnyContent(event.getGzhContentItemId(), ws, "gzh");
        return content != null ? content : findAnyContent(event.getXhsContentItemId(), ws, "xhs");
    }

    private ContentItemEntity findAnyContent(Long contentId, long workspaceId, String platform) {
        if (contentId == null) return null;
        ContentItemEntity content = contentItemMapper.selectOne(new LambdaQueryWrapper<ContentItemEntity>()
                .eq(ContentItemEntity::getId, contentId)
                .eq(ContentItemEntity::getWorkspaceId, workspaceId)
                .eq(ContentItemEntity::getDeleted, 0));
        return content == null || (content.getPlatform() != null
                && !platform.equalsIgnoreCase(content.getPlatform())) ? null : content;
    }

    private ContentItemEntity findPlatformArtifact(Long contentId, long workspaceId, String platform) {
        if (contentId == null) return null;
        ContentItemEntity content = contentItemMapper.selectOne(new LambdaQueryWrapper<ContentItemEntity>()
                .eq(ContentItemEntity::getId, contentId)
                .eq(ContentItemEntity::getWorkspaceId, workspaceId)
                .eq(ContentItemEntity::getDeleted, 0));
        if (content == null || (content.getPlatform() != null
                && !platform.equalsIgnoreCase(content.getPlatform()))) return null;
        if (!"published".equalsIgnoreCase(content.getStatus())
                || content.getExternalRef() == null || content.getExternalRef().isBlank()
                || content.getArtifactHash() == null || content.getArtifactHash().isBlank()) return null;
        return content;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            // SHA-256 is required by the JDK.  A short hashCode fallback would
            // turn a persistence identity into a silent collision hazard.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Stable identity for one claim/evidence packet.  Keep the algorithm name
     * in the material so a future normalization change cannot silently merge
     * packets written by an older release.
     */
    static String evidenceIdentityHash(String canonicalUrl, String claim,
                                       String quote, Long captureId) {
        return sha256("evidence-v1|" + identityPart(canonicalUrl)
                + identityPart(claim)
                + identityPart(AiNewsSourceDocumentParser.normalizeText(quote))
                + identityPart(captureId == null ? "" : String.valueOf(captureId)));
    }

    private static String identityPart(String value) {
        String normalized = value == null ? "" : AiNewsSourceDocumentParser.normalizeText(value);
        return normalized.length() + ":" + normalized + "|";
    }

    private static boolean sameEvidenceIdentity(AiNewsEvidenceEntity existing,
                                                String url, String claim,
                                                String quote, Long captureId) {
        if (existing == null) return false;
        return Objects.equals(existing.getSourceUrl(), url)
                && Objects.equals(existing.getClaim(), claim)
                && Objects.equals(AiNewsSourceDocumentParser.normalizeText(existing.getQuote()),
                        AiNewsSourceDocumentParser.normalizeText(quote))
                && Objects.equals(existing.getSourceCaptureId(), captureId);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new NewsClawException(400, "事件结构化字段格式无效");
        }
    }

    private static String trimTo(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0D;
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static boolean captureBound(AiNewsEvidenceEntity item) {
        return item != null && item.getSourceCaptureId() != null && item.getFetchedAt() != null
                && item.getHttpStatus() != null && item.getHttpStatus() >= 200 && item.getHttpStatus() < 300
                && item.getContentHash() != null && item.getContentHash().length() == 64
                && firstNonBlank(item.getFinalUrl(), item.getSourceUrl()) != null;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) return primary;
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    private static String defaultToken(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean hasConflicts(String conflictsJson) {
        if (conflictsJson == null || conflictsJson.isBlank()) return false;
        try {
            var node = objectMapper.readTree(conflictsJson);
            return node != null && (!node.isArray() || !node.isEmpty());
        } catch (JsonProcessingException invalidJson) {
            // A malformed conflict payload is unsafe to treat as "no conflict".
            return true;
        }
    }

    private void audit(String action, AiNewsEventEntity event, Map<String, Object> detail) {
        if (auditEventService == null || event == null || event.getId() == null) return;
        auditEventService.recordSync(action, "AI_NEWS_EVENT", String.valueOf(event.getId()),
                event.getTitle(), writeJson(detail), workspace(event.getWorkspaceId()));
    }

    public record WindowSummary(String mode,
                                Instant windowStart,
                                Instant windowEnd,
                                int persistedEventCount,
                                Map<String, Long> statusCounts,
                                Map<String, Long> categoryCounts,
                                int evidenceCount,
                                long captureBoundEvidenceCount,
                                long verifiedEventCount,
                                long officialSourceEventCount,
                                long trustedMediaEventCount,
                                long attestedSupportEventCount,
                                long pendingReviewEventCount,
                                Map<String, Long> reviewReasonCounts,
                                String scopeNote) {
        public WindowSummary {
            statusCounts = statusCounts == null ? Map.of() : Map.copyOf(statusCounts);
            categoryCounts = categoryCounts == null ? Map.of() : Map.copyOf(categoryCounts);
            reviewReasonCounts = reviewReasonCounts == null ? Map.of() : Map.copyOf(reviewReasonCounts);
        }
    }

}

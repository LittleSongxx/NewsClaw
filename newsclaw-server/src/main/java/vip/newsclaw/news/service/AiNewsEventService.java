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
import vip.newsclaw.audit.service.AuditEventService;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.content.model.ContentItemEntity;
import vip.newsclaw.content.repository.ContentItemMapper;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEvidenceCaptureTrace;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
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
    private final AiNewsReviewRoutingService reviewRoutingService;

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
        query.orderByDesc(AiNewsEventEntity::getDiscoveredAt)
                .orderByDesc(AiNewsEventEntity::getCreateTime);
        IPage<AiNewsEventEntity> result = eventMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 100)), query);
        populateEvidenceSummary(ws, result.getRecords());
        populateReviewSummary(ws, result.getRecords());
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
        return new AiNewsEventDetail(event, evidence,
                captureAttemptService == null ? List.of()
                        : captureAttemptService.list(workspace(event.getWorkspaceId()), id));
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
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new NewsClawException(400, "AI 动态事件标题不能为空");
        }
        List<AiNewsEvidenceRequest> incomingEvidence = request.evidence() == null
                ? List.of() : request.evidence();
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
            appendEvidence(event, ws, incomingEvidence);
            return synchronizeReviewTask(event);
        } else if (AiNewsEventStatus.ARCHIVED.token().equals(event.getStatus())) {
            throw new NewsClawException(409, "已归档事件不能重新写入，请先恢复或创建新的事件");
        }
        event.setTitle(request.title().trim());
        event.setSummary(trimTo(request.summary(), 12000));
        event.setCategory(normalizeCategory(request.category()));
        event.setEntitiesJson(writeJson(request.entities() == null ? List.of() : request.entities()));
        event.setClaimsJson(writeJson(request.claims() == null ? List.of() : request.claims()));
        event.setConflictsJson(writeJson(request.conflicts() == null ? List.of() : request.conflicts()));
        event.setDiscoveredAt(request.discoveredAt() == null ? now : request.discoveredAt());
        if (request.publishedAt() != null) {
            event.setPublishedAt(request.publishedAt());
        }
        // Updating evidence or claims invalidates a prior verification.  A
        // reviewer must see the new packet before it can reach production.
        String previousStatus = event.getStatus();
        if (event.getId() != null
                && !AiNewsEventStatus.CANDIDATE.token().equals(event.getStatus())
                && !AiNewsEventStatus.PUBLISHED.token().equals(event.getStatus())
                && (!incomingEvidence.isEmpty() || request.claims() != null || request.conflicts() != null)) {
            event.setStatus(AiNewsEventStatus.RESEARCHING.token());
            event.setConfidence(0.0D);
        }
        boolean hasIncomingConflicts = request.conflicts() != null
                && request.conflicts().stream().anyMatch(value -> value != null && !value.isBlank());
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
        appendEvidence(event, ws, incomingEvidence);
        audit(created ? "ai-news.event.created" : "ai-news.event.updated",
                event, Map.of("status", event.getStatus(), "evidenceCount", incomingEvidence.size()));
        if (!Objects.equals(previousStatus, event.getStatus())) {
            audit("ai-news.event.status-changed", event,
                    Map.of("from", previousStatus == null ? "" : previousStatus,
                            "to", event.getStatus(), "reason", "evidence-or-claims-updated"));
        }
        return synchronizeReviewTask(event);
    }

    @Transactional
    public AiNewsEventEntity verify(Long workspaceId, Long id, String verdict, Double confidence) {
        AiNewsEventEntity event = findEvent(workspaceId, id);
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
        VerificationResult result = evaluate(evidence, event.getConflictsJson());
        if (!result.eligible()) {
            throw new NewsClawException(409, result.reason());
        }
        for (AiNewsEvidenceEntity item : evidence) {
            if (isTrustedVerificationEvidence(item) && !Boolean.TRUE.equals(item.getVerified())) {
                item.setVerified(true);
                item.setUpdateTime(LocalDateTime.now());
                evidenceMapper.updateById(item);
            }
        }
        return transition(event, AiNewsEventStatus.VERIFIED,
                confidence != null ? clamp(confidence) : result.confidence());
    }

    @Transactional
    public AiNewsEventEntity dismiss(Long workspaceId, Long id) {
        AiNewsEventEntity event = findEvent(workspaceId, id);
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
        AiNewsEventEntity event = findEvent(workspaceId, id);
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
        AiNewsEventEntity event = findEvent(workspaceId, id);
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
        AiNewsEventEntity event = findEvent(workspaceId, id);
        if (AiNewsEventStatus.PUBLISHED.token().equals(event.getStatus())) return synchronizeReviewTask(event);
        ensureMutable(event);
        if (!AiNewsEventStatus.IN_PRODUCTION.token().equals(event.getStatus())) {
            throw new NewsClawException(409, "只有进入内容生产的事件才能标记已交付");
        }
        if (!hasActiveDeliveryArtifact(event)) {
            throw new NewsClawException(409, "至少关联一个公众号或小红书内容条目后才能标记已交付");
        }
        event.setPublishedAt(LocalDateTime.now());
        AiNewsEventEntity published = transition(event, AiNewsEventStatus.PUBLISHED, event.getConfidence());
        audit("ai-news.event.published", published,
                Map.of("gzhContentItemId", published.getGzhContentItemId() == null ? 0L : published.getGzhContentItemId(),
                        "xhsContentItemId", published.getXhsContentItemId() == null ? 0L : published.getXhsContentItemId()));
        return published;
    }

    @Transactional
    public AiNewsEventEntity linkRun(Long workspaceId, Long id, Long runId) {
        AiNewsEventEntity event = findEvent(workspaceId, id);
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
        AiNewsEventEntity event = findEvent(workspaceId, id);
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
        AiNewsEventEntity event = findEvent(workspaceId, id);
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
        AiNewsEventEntity event = findEvent(workspaceId, id);
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
        AiNewsEventEntity event = findEvent(workspaceId, eventId);
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
        synchronizeReviewTask(event);
        return evidence;
    }

    private List<AiNewsEvidenceEntity> appendEvidence(AiNewsEventEntity event, long workspaceId,
                                                       Collection<AiNewsEvidenceRequest> inputs) {
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
            AiNewsEvidenceEntity existing = evidenceMapper.selectOne(
                    new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                            .eq(AiNewsEvidenceEntity::getEventId, event.getId())
                            .eq(AiNewsEvidenceEntity::getWorkspaceId, workspaceId)
                            // The hash is the bounded/indexed lookup key. The
                            // URL fallback keeps upgrades tolerant of rows
                            // written before V190 was applied.
                            .and(q -> q.eq(AiNewsEvidenceEntity::getSourceUrlHash, urlHash)
                                    .or().eq(AiNewsEvidenceEntity::getSourceUrl, url)));
            AiNewsEvidenceEntity row = existing == null ? new AiNewsEvidenceEntity() : existing;
            boolean changed = existing == null
                    || !Objects.equals(existing.getClaim(), input.claim().trim())
                    || !Objects.equals(existing.getSourceTier(), tier.token())
                    || !Objects.equals(existing.getQuote(), trimTo(input.quote(), 12000));
            row.setEventId(event.getId());
            row.setWorkspaceId(workspaceId);
            row.setSourceUrl(url);
            row.setSourceUrlHash(urlHash);
            row.setSourceTitle(trimTo(input.sourceTitle(), 512));
            row.setSourcePublishedAt(input.sourcePublishedAt());
            row.setSourceTier(tier.token());
            row.setClaim(trimTo(input.claim(), 12000));
            row.setQuote(trimTo(input.quote(), 12000));
            row.setConfidence(clamp(input.confidence() == null ? 0.0D : input.confidence()));
            if (row.getVerified() == null || changed) row.setVerified(false);
            row.setDeleted(0);
            row.setUpdateTime(LocalDateTime.now());
            if (row.getCreateTime() == null) row.setCreateTime(LocalDateTime.now());
            if (row.getId() == null) evidenceMapper.insert(row); else evidenceMapper.updateById(row);
            written.add(row);
        }
        return written;
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

    private VerificationResult evaluate(List<AiNewsEvidenceEntity> evidence, String conflictsJson) {
        if (evidence == null || evidence.isEmpty()) {
            return new VerificationResult(false, "至少需要一条来源证据", 0.0D);
        }
        if (hasConflicts(conflictsJson)) {
            return new VerificationResult(false, "事件存在未解决的来源冲突，请先标记冲突或修正 claims", 0.0D);
        }
        boolean official = evidence.stream().anyMatch(this::isTrustedOfficialEvidence);
        Set<String> trustedMediaSources = evidence.stream()
                .filter(item -> item != null && "media".equalsIgnoreCase(item.getSourceTier()))
                .map(item -> sourceRegistry.trustedMediaSourceKey(item.getSourceUrl()).orElse(""))
                .filter(sourceKey -> !sourceKey.isBlank())
                .collect(Collectors.toSet());
        boolean corroborated = trustedMediaSources.size() >= 2;
        if (!official && !corroborated) {
            return new VerificationResult(false,
                    "关键事件需要一个注册官方来源，或两个来源注册表中的独立可信媒体；未注册来源只能作为线索",
                    0.0D);
        }
        double average = evidence.stream().filter(this::isTrustedVerificationEvidence)
                .map(AiNewsEvidenceEntity::getConfidence)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.5D);
        double score = official ? Math.max(average, 0.75D) : Math.max(average, 0.6D);
        return new VerificationResult(true, "ok", clamp(score));
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
        if (category == null || category.isBlank()) return "model";
        String value = category.trim().toLowerCase(Locale.ROOT);
        return Set.of("model", "robotics", "infrastructure", "product", "open_source", "industry", "policy")
                .contains(value) ? value : "model";
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
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            String authority = host + (port > 0 ? ":" + port : "");
            return scheme + "://" + authority + path + query;
        } catch (URISyntaxException e) {
            return input.split("#", 2)[0];
        }
    }

    private boolean isTrustedOfficialEvidence(AiNewsEvidenceEntity evidence) {
        if (evidence == null || !"official".equalsIgnoreCase(evidence.getSourceTier())) return false;
        return sourceRegistry.isOfficialUrl(evidence.getSourceUrl());
    }

    private boolean isTrustedVerificationEvidence(AiNewsEvidenceEntity evidence) {
        if (isTrustedOfficialEvidence(evidence)) return true;
        return evidence != null && "media".equalsIgnoreCase(evidence.getSourceTier())
                && sourceRegistry.isTrustedMediaUrl(evidence.getSourceUrl());
    }

    /**
     * Delivery acknowledgement must point at a live content-calendar row. The
     * fallback for mapper-less unit construction preserves the service's
     * small pure-state tests while the Spring path enforces workspace and
     * soft-delete isolation.
     */
    private boolean hasActiveDeliveryArtifact(AiNewsEventEntity event) {
        if (contentItemMapper == null) {
            return event.getGzhContentItemId() != null || event.getXhsContentItemId() != null;
        }
        long ws = workspace(event.getWorkspaceId());
        return isActiveContent(event.getGzhContentItemId(), ws, "gzh")
                || isActiveContent(event.getXhsContentItemId(), ws, "xhs");
    }

    private boolean isActiveContent(Long contentId, long workspaceId, String platform) {
        if (contentId == null) return false;
        ContentItemEntity content = contentItemMapper.selectOne(new LambdaQueryWrapper<ContentItemEntity>()
                .eq(ContentItemEntity::getId, contentId)
                .eq(ContentItemEntity::getWorkspaceId, workspaceId)
                .eq(ContentItemEntity::getDeleted, 0));
        return content != null && (content.getPlatform() == null
                || platform.equalsIgnoreCase(content.getPlatform()));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
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
        try {
            auditEventService.record(action, "AI_NEWS_EVENT", String.valueOf(event.getId()),
                    event.getTitle(), writeJson(detail), workspace(event.getWorkspaceId()));
        } catch (Exception ex) {
            // Audit is best effort and must never roll back the event state.
            log.warn("[AiNews] audit write failed for {}: {}", action, ex.getMessage());
        }
    }

    private record VerificationResult(boolean eligible, String reason, double confidence) {
    }
}

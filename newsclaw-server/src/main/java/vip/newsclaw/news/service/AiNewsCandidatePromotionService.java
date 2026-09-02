package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.audit.service.AuditEventService;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsCandidateEntity;
import vip.newsclaw.news.model.AiNewsCandidatePromotionRequest;
import vip.newsclaw.news.model.AiNewsEvidenceRelation;
import vip.newsclaw.news.model.AiNewsEvidenceRequest;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEventUpsertRequest;
import vip.newsclaw.news.model.AiNewsScanRunEntity;
import vip.newsclaw.news.repository.AiNewsCandidateMapper;
import vip.newsclaw.news.repository.AiNewsScanRunMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Explicit bridge from the candidate funnel to the event/evidence aggregate.
 * Promotion is an editorial choice, not verification: the resulting event
 * always starts as {@code candidate} and must pass the existing review gate.
 */
@Service
@Slf4j
public class AiNewsCandidatePromotionService {

    private static final long DEFAULT_WORKSPACE = 1L;
    private static final Set<String> PROMOTABLE_RUN_STATUSES = Set.of(
            "CANDIDATES_PERSISTED", "CAPTURE_PENDING", "COMPLETED");

    private final AiNewsCandidateMapper candidateMapper;
    private final AiNewsScanRunMapper scanMapper;
    private final AiNewsEventService eventService;

    @Autowired(required = false)
    private AiNewsSourceCaptureService sourceCaptureService;

    @Autowired(required = false)
    private AuditEventService auditEventService;

    public AiNewsCandidatePromotionService(AiNewsCandidateMapper candidateMapper,
                                           AiNewsScanRunMapper scanMapper,
                                           AiNewsEventService eventService) {
        this.candidateMapper = candidateMapper;
        this.scanMapper = scanMapper;
        this.eventService = eventService;
    }

    void setSourceCaptureService(AiNewsSourceCaptureService sourceCaptureService) {
        this.sourceCaptureService = sourceCaptureService;
    }

    @Transactional
    // ponytail: one JVM-wide lock keeps fact-key promotion idempotent; replace
    // with a small DB advisory/lock table if multiple app nodes promote at once.
    public synchronized AiNewsEventEntity promote(Long workspaceId, Long candidateId,
                                     AiNewsCandidatePromotionRequest request) {
        long ws = workspace(workspaceId);
        if (candidateId == null) throw new NewsClawException(400, "candidate id is required");
        // failScan/recovery take the owning run lock before touching its
        // candidates.  Read the run id without a lock, then acquire locks in
        // that same order and re-read the candidate before every gate.  This
        // avoids the candidate -> run / run -> candidate deadlock while still
        // making the final decision against a locked, workspace-scoped row.
        AiNewsCandidateEntity candidate = candidateMapper.selectOne(
                new LambdaQueryWrapper<AiNewsCandidateEntity>()
                        .eq(AiNewsCandidateEntity::getId, candidateId)
                        .eq(AiNewsCandidateEntity::getWorkspaceId, ws)
                        .eq(AiNewsCandidateEntity::getDeleted, 0));
        if (candidate == null) throw new NewsClawException(404, "candidate not found");
        if (candidate.getEventId() != null) {
            return eventService.findEvent(ws, candidate.getEventId());
        }
        if (candidate.getScanRunId() == null) {
            throw new NewsClawException(409, "候选所属扫描不存在或 workspace 不一致");
        }
        AiNewsScanRunEntity run = scanMapper.selectForUpdate(candidate.getScanRunId(), ws);
        if (run == null) {
            throw new NewsClawException(409, "候选所属扫描不存在或 workspace 不一致");
        }
        candidate = candidateMapper.selectForUpdate(candidateId, ws);
        if (candidate == null) throw new NewsClawException(404, "candidate not found");
        // A concurrent repair/mutation must not make us apply the gates to a
        // different run or silently replace an already linked event.
        if (candidate.getEventId() != null) {
            return eventService.findEvent(ws, candidate.getEventId());
        }
        if (!java.util.Objects.equals(candidate.getScanRunId(), run.getId())) {
            throw new NewsClawException(409, "候选所属扫描在 promotion 期间发生变化");
        }
        if (!"SELECTED".equalsIgnoreCase(candidate.getSelectionStatus())) {
            throw new NewsClawException(409, "只有 selected 候选才能形成事件");
        }
        if (!"ACCEPTED".equalsIgnoreCase(candidate.getReviewStatus())) {
            throw new NewsClawException(409, "候选必须先完成人工采用审核");
        }
        if (candidate.getReviewedBy() == null || candidate.getReviewedBy().isBlank()
                || candidate.getReviewedAt() == null
                || candidate.getReviewOrigin() == null
                || !candidate.getReviewOrigin().toUpperCase(Locale.ROOT).startsWith("HUMAN")) {
            throw new NewsClawException(409,
                    "候选采用结论缺少可验证的人工操作者，不能 promotion");
        }
        if (!"SUCCESS".equalsIgnoreCase(candidate.getCaptureStatus())
                || candidate.getCaptureId() == null || candidate.getCaptureId() <= 0) {
            throw new NewsClawException(409, "候选尚未得到可绑定的成功 capture");
        }
        // The owning run is already locked before this point.  A plain
        // selectById would let failScan/recovery change it between the check
        // and event insertion, leaving an event linked to a failed run.
        if (!PROMOTABLE_RUN_STATUSES.contains(normalize(run.getRunStatus()))) {
            throw new NewsClawException(409, "候选所属扫描尚未完成发现落库，不能形成事件");
        }
        if (run.getWindowStart() == null || run.getWindowEnd() == null) {
            throw new NewsClawException(409, "候选所属扫描缺少冻结来源时间窗");
        }
        if (request == null) throw new NewsClawException(400, "promotion request is required");
        Instant windowStart = run.getWindowStart().toInstant(ZoneOffset.UTC);
        Instant windowEnd = run.getWindowEnd().toInstant(ZoneOffset.UTC);
        AiNewsAtomicFactGuard.AtomicFact fact;
        try {
            fact = AiNewsAtomicFactGuard.prepare(request.category(), request.entities(),
                    request.claim(), windowStart);
        } catch (IllegalArgumentException error) {
            throw new NewsClawException(400, error.getMessage());
        }
        validateRelation(request.semanticRelation(), request.relationConfidence());
        if (sourceCaptureService == null) {
            throw new NewsClawException(503, "promotion 所需的 source capture 服务不可用");
        }
        AiNewsSourceCaptureService.BoundCapture bound = sourceCaptureService.bind(
                ws, candidate.getCaptureId(), request.quote(), windowStart, windowEnd);
        String candidateUrl = AiNewsDiscoverySearchService.canonicalDiscoveryUrl(candidate.getCanonicalUrl());
        String captureUrl = AiNewsDiscoverySearchService.canonicalDiscoveryUrl(
                bound.capture().getFinalUrl());
        if (candidateUrl.isBlank() || captureUrl.isBlank()
                || !AiNewsDiscoverySearchService.discoveryUrlAliasKey(candidateUrl)
                .equals(AiNewsDiscoverySearchService.discoveryUrlAliasKey(captureUrl))) {
            throw new NewsClawException(409,
                    "candidate URL 与成功 capture 的最终 URL 不一致，拒绝形成事件");
        }
        String eventKey = "candidate-fact:" + fact.eventKeyMaterial();
        // A second candidate for the same atomic fact must attach to the
        // existing event instead of re-upserting it (which would otherwise
        // reopen a verified event merely because another source was selected).
        AiNewsEventEntity existing = eventService.findEventByKey(ws, eventKey);
        if (existing != null) {
            if (!Set.of("candidate", "researching")
                    .contains(normalize(existing.getStatus()).toLowerCase(Locale.ROOT))) {
                throw new NewsClawException(409,
                        "相同原子事实已进入核验或发布生命周期，不能通过 promotion 追加证据");
            }
            AiNewsEventEntity enriched = eventService.appendCapturedEvidence(ws, existing.getId(),
                    promotionEvidence(fact, bound, request), windowStart, windowEnd);
            if (enriched != null) existing = enriched;
            int linkedExisting = candidateMapper.linkPromotedEvent(candidateId, ws, existing.getId(),
                    candidate.getCaptureId(), now());
            if (linkedExisting == 1) {
                audit(candidate, existing, ws);
                return existing;
            }
            AiNewsCandidateEntity current = candidateMapper.selectOne(
                    new LambdaQueryWrapper<AiNewsCandidateEntity>()
                            .eq(AiNewsCandidateEntity::getId, candidateId)
                            .eq(AiNewsCandidateEntity::getWorkspaceId, ws)
                            .eq(AiNewsCandidateEntity::getDeleted, 0));
            if (current != null && current.getEventId() != null) {
                return eventService.findEvent(ws, current.getEventId());
            }
            throw new NewsClawException(409, "candidate promotion was changed concurrently");
        }
        LocalDateTime discoveredAt = candidate.getLastSeenAt() == null
                ? run.getStartedAt() : candidate.getLastSeenAt();
        AiNewsEventEntity event = eventService.upsertCaptured(ws,
                new AiNewsEventUpsertRequest(eventKey,
                        fact.title(), fact.summary(), fact.category(), fact.entities(), discoveredAt,
                        null, List.of(fact.summary()), List.of(),
                        List.of(promotionEvidence(fact, bound, request, candidate.getCaptureId()))),
                windowStart, windowEnd);
        if (event == null || event.getId() == null) {
            throw new NewsClawException(500, "promotion did not create an event");
        }
        int linked = candidateMapper.linkPromotedEvent(candidateId, ws, event.getId(),
                candidate.getCaptureId(), now());
        if (linked == 0) {
            AiNewsCandidateEntity current = candidateMapper.selectOne(
                    new LambdaQueryWrapper<AiNewsCandidateEntity>()
                            .eq(AiNewsCandidateEntity::getId, candidateId)
                            .eq(AiNewsCandidateEntity::getWorkspaceId, ws)
                            .eq(AiNewsCandidateEntity::getDeleted, 0));
            if (current != null && current.getEventId() != null) {
                return eventService.findEvent(ws, current.getEventId());
            }
            throw new NewsClawException(409, "candidate promotion was changed concurrently");
        }
        audit(candidate, event, ws);
        return event;
    }

    private static AiNewsEvidenceRequest promotionEvidence(
            AiNewsAtomicFactGuard.AtomicFact fact,
            AiNewsSourceCaptureService.BoundCapture bound,
            AiNewsCandidatePromotionRequest request) {
        return promotionEvidence(fact, bound, request, bound.capture().getId());
    }

    private static AiNewsEvidenceRequest promotionEvidence(
            AiNewsAtomicFactGuard.AtomicFact fact,
            AiNewsSourceCaptureService.BoundCapture bound,
            AiNewsCandidatePromotionRequest request,
            Long captureId) {
        return new AiNewsEvidenceRequest(null, null, null, "media", fact.summary(),
                bound.authoritativeQuote(), 0.5D, request.semanticRelation(),
                request.relationConfidence(), captureId);
    }

    private static void validateRelation(String relation, Double confidence) {
        try {
            AiNewsEvidenceRelation.from(relation);
        } catch (IllegalArgumentException error) {
            throw new NewsClawException(400,
                    "semanticRelation 必须是 entails、contradicts、partial、unrelated、hedged 或 unknown");
        }
        if (confidence != null && (confidence.isNaN() || confidence.isInfinite()
                || confidence < 0.0D || confidence > 1.0D)) {
            throw new NewsClawException(400, "relationConfidence 必须在 0 到 1 之间");
        }
    }

    private void audit(AiNewsCandidateEntity candidate, AiNewsEventEntity event, long workspace) {
        if (auditEventService == null) return;
        auditEventService.recordSync("ai-news.candidate.promoted", "AI_NEWS_CANDIDATE",
                String.valueOf(candidate.getId()), candidate.getTitle(),
                "{\"eventId\":" + event.getId() + "}", workspace);
    }

    private static long workspace(Long value) {
        return value == null || value <= 0 ? DEFAULT_WORKSPACE : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}

package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsCaptureAttemptEntity;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsReviewTaskEntity;
import vip.newsclaw.news.model.AiNewsReviewTaskStatus;
import vip.newsclaw.news.repository.AiNewsCaptureAttemptMapper;
import vip.newsclaw.news.repository.AiNewsEvidenceMapper;
import vip.newsclaw.news.repository.AiNewsEventMapper;
import vip.newsclaw.news.repository.AiNewsReviewTaskMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Persists and gates the deterministic AI-news human-review queue.
 *
 * <p>The policy is re-evaluated before production, so an old or manually
 * absent notification card cannot be used to bypass a newly introduced risk.
 * A resolved task stays resolved only while its policy version and risk
 * fingerprint still match the current event packet.</p>
 */
@Slf4j
@Service
public class AiNewsReviewRoutingService {

    private static final long DEFAULT_WORKSPACE = 1L;
    private static final String ROUTE_SOURCE = "DETERMINISTIC_POLICY";

    private final AiNewsReviewTaskMapper taskMapper;
    private final AiNewsEventMapper eventMapper;
    private final AiNewsEvidenceMapper evidenceMapper;
    private final AiNewsCaptureAttemptMapper captureAttemptMapper;
    private final AiNewsReviewPolicy policy;
    private final ObjectMapper objectMapper;

    public AiNewsReviewRoutingService(AiNewsReviewTaskMapper taskMapper,
                                      AiNewsEventMapper eventMapper,
                                      AiNewsEvidenceMapper evidenceMapper,
                                      AiNewsCaptureAttemptMapper captureAttemptMapper,
                                      AiNewsReviewPolicy policy,
                                      ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.evidenceMapper = evidenceMapper;
        this.captureAttemptMapper = captureAttemptMapper;
        this.policy = policy;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiNewsReviewTaskEntity sync(Long workspaceId, Long eventId) {
        long ws = workspace(workspaceId);
        AiNewsEventEntity event = eventMapper.selectForUpdate(ws, eventId);
        if (event == null) {
            event = eventMapper.selectOne(new LambdaQueryWrapper<AiNewsEventEntity>()
                    .eq(AiNewsEventEntity::getWorkspaceId, ws)
                    .eq(AiNewsEventEntity::getId, eventId)
                    .eq(AiNewsEventEntity::getDeleted, 0));
        }
        return event == null ? null : sync(event);
    }

    @Transactional
    public AiNewsReviewTaskEntity sync(AiNewsEventEntity event) {
        if (event == null || event.getId() == null) return null;
        long ws = workspace(event.getWorkspaceId());
        List<AiNewsEvidenceEntity> evidence = evidenceMapper.selectList(
                new LambdaQueryWrapper<AiNewsEvidenceEntity>()
                        .eq(AiNewsEvidenceEntity::getWorkspaceId, ws)
                        .eq(AiNewsEvidenceEntity::getEventId, event.getId())
                        .eq(AiNewsEvidenceEntity::getDeleted, 0));
        List<AiNewsCaptureAttemptEntity> attempts = captureAttemptMapper.selectList(
                new LambdaQueryWrapper<AiNewsCaptureAttemptEntity>()
                        .eq(AiNewsCaptureAttemptEntity::getWorkspaceId, ws)
                        .eq(AiNewsCaptureAttemptEntity::getEventId, event.getId())
                        .eq(AiNewsCaptureAttemptEntity::getDeleted, 0));
        return sync(event, evidence, attempts);
    }

    /** Exposed for focused policy/routing tests without a database fixture. */
    @Transactional
    public AiNewsReviewTaskEntity sync(AiNewsEventEntity event,
                                       List<AiNewsEvidenceEntity> evidence,
                                       List<AiNewsCaptureAttemptEntity> attempts) {
        if (event == null || event.getId() == null) return null;
        long ws = workspace(event.getWorkspaceId());
        AiNewsReviewPolicy.Decision decision = policy.evaluate(event, evidence, attempts);
        AiNewsReviewTaskEntity existing = findTaskForUpdate(ws, event.getId());
        if (!decision.requiresReview()) {
            AiNewsReviewTaskEntity closed = noLongerRequired(existing, decision);
            applyProjection(event, closed);
            return closed;
        }

        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            AiNewsReviewTaskEntity created = new AiNewsReviewTaskEntity();
            created.setWorkspaceId(ws);
            created.setEventId(event.getId());
            created.setStatus(AiNewsReviewTaskStatus.PENDING.name());
            created.setReasonsJson(writeReasons(decision.reasonCodes()));
            created.setPolicyVersion(AiNewsReviewPolicy.VERSION);
            created.setRiskFingerprint(decision.fingerprint());
            created.setRouteSource(ROUTE_SOURCE);
            created.setCreateTime(now);
            created.setUpdateTime(now);
            created.setDeleted(0);
            taskMapper.insert(created);
            applyProjection(event, created);
            return created;
        }

        AiNewsReviewTaskStatus status = AiNewsReviewTaskStatus.from(existing.getStatus());
        boolean changedPacket = !Objects.equals(AiNewsReviewPolicy.VERSION, existing.getPolicyVersion())
                || !Objects.equals(decision.fingerprint(), existing.getRiskFingerprint());
        boolean reopen = status != AiNewsReviewTaskStatus.PENDING
                && (status != AiNewsReviewTaskStatus.RESOLVED || changedPacket);
        if (status == AiNewsReviewTaskStatus.PENDING || reopen || changedPacket) {
            existing.setStatus(AiNewsReviewTaskStatus.PENDING.name());
            existing.setReasonsJson(writeReasons(decision.reasonCodes()));
            existing.setPolicyVersion(AiNewsReviewPolicy.VERSION);
            existing.setRiskFingerprint(decision.fingerprint());
            existing.setRouteSource(ROUTE_SOURCE);
            if (reopen || changedPacket) {
                existing.setResolvedAt(null);
                existing.setResolvedBy(null);
                existing.setResolutionNote(null);
                existing.setCardIssuedAt(null);
                existing.setCardDeliveryError(null);
            }
            existing.setUpdateTime(now);
            taskMapper.updateById(existing);
        }
        applyProjection(event, existing);
        return existing;
    }

    @Transactional
    public void requireClearForProduction(AiNewsEventEntity event) {
        AiNewsReviewTaskEntity task = sync(event);
        if (task != null && AiNewsReviewTaskStatus.PENDING.name().equals(task.getStatus())) {
            String reasons = String.join(", ", readReasons(task.getReasonsJson()));
            throw new NewsClawException(409, "事件仍有待处理的人工复核风险: "
                    + (reasons.isBlank() ? "UNKNOWN" : reasons));
        }
    }

    @Transactional
    public AiNewsReviewTaskEntity resolve(Long workspaceId, Long eventId,
                                          String operator, String note) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, eventId);
        AiNewsReviewTaskEntity task = sync(event);
        if (task == null) {
            throw new NewsClawException(409, "当前事件没有待解决的人工复核任务");
        }
        if (AiNewsReviewTaskStatus.RESOLVED.name().equals(task.getStatus())) return task;
        if (!AiNewsReviewTaskStatus.PENDING.name().equals(task.getStatus())) {
            throw new NewsClawException(409, "当前复核任务不处于待处理状态");
        }
        if (!"verified".equalsIgnoreCase(event.getStatus())) {
            throw new NewsClawException(409, "只有已核验事件才能完成高风险人工复核");
        }
        String resolvedOperator = requiredOperator(operator);
        String resolvedNote = requiredNote(note);
        task.setStatus(AiNewsReviewTaskStatus.RESOLVED.name());
        task.setResolvedBy(resolvedOperator);
        task.setResolutionNote(resolvedNote);
        task.setResolvedAt(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        applyProjection(event, task);
        return task;
    }

    /**
     * Used only by an authenticated interactive card click after the event
     * verification state-machine action has completed. It is intentionally a
     * no-op when no risk task is required.
     */
    @Transactional
    public AiNewsReviewTaskEntity resolveIfPending(Long workspaceId, Long eventId,
                                                   String operator, String note) {
        AiNewsEventEntity event = findEventForUpdate(workspaceId, eventId);
        AiNewsReviewTaskEntity task = sync(event);
        if (task == null || !AiNewsReviewTaskStatus.PENDING.name().equals(task.getStatus())) return task;
        return resolve(workspaceId, eventId, operator, note);
    }

    public IPage<AiNewsReviewTaskEntity> page(Long workspaceId, int page, int size, String status) {
        long ws = workspace(workspaceId);
        LambdaQueryWrapper<AiNewsReviewTaskEntity> query = baseQuery(ws);
        if (status != null && !status.isBlank()) {
            AiNewsReviewTaskStatus parsed = AiNewsReviewTaskStatus.from(status);
            if (parsed == null) throw new NewsClawException(400,
                    "status 必须是 PENDING、RESOLVED 或 NO_LONGER_REQUIRED");
            query.eq(AiNewsReviewTaskEntity::getStatus, parsed.name());
        }
        query.orderByAsc(AiNewsReviewTaskEntity::getStatus)
                .orderByDesc(AiNewsReviewTaskEntity::getUpdateTime);
        return taskMapper.selectPage(new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 100)), query);
    }

    public AiNewsReviewTaskEntity get(Long workspaceId, Long eventId) {
        AiNewsReviewTaskEntity task = findTask(workspace(workspaceId), eventId);
        if (task == null) throw new NewsClawException(404, "AI 动态复核任务不存在");
        return task;
    }

    @Transactional
    public void recordCardDispatch(Long workspaceId, Long eventId, boolean delivered, String error) {
        AiNewsReviewTaskEntity task = findTaskForUpdate(workspace(workspaceId), eventId);
        if (task == null || !AiNewsReviewTaskStatus.PENDING.name().equals(task.getStatus())) return;
        task.setUpdateTime(LocalDateTime.now());
        if (delivered) {
            task.setCardIssuedAt(LocalDateTime.now());
            task.setCardDeliveryError(null);
        } else {
            task.setCardDeliveryError(trim(error, 2000, "card delivery failed"));
        }
        taskMapper.updateById(task);
    }

    /** Populate transient event fields from persisted queue state without GET side effects. */
    public void populateProjection(Long workspaceId, List<AiNewsEventEntity> events) {
        if (events == null || events.isEmpty()) return;
        long ws = workspace(workspaceId);
        List<Long> ids = events.stream().map(AiNewsEventEntity::getId)
                .filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        Map<Long, AiNewsReviewTaskEntity> byEvent = taskMapper.selectList(baseQuery(ws)
                        .in(AiNewsReviewTaskEntity::getEventId, ids))
                .stream().collect(Collectors.toMap(AiNewsReviewTaskEntity::getEventId,
                        item -> item, (left, right) -> left, LinkedHashMap::new));
        for (AiNewsEventEntity event : events) {
            applyProjection(event, byEvent.get(event.getId()));
        }
    }

    public List<String> readReasons(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<String> values = objectMapper.readValue(raw, new TypeReference<List<String>>() { });
            return values == null ? List.of() : values.stream().filter(item -> item != null && !item.isBlank()).toList();
        } catch (Exception e) {
            log.warn("Unable to parse AI-news review reasons: {}", e.getMessage());
            return List.of("UNREADABLE_REASONS");
        }
    }

    private AiNewsReviewTaskEntity noLongerRequired(AiNewsReviewTaskEntity task,
                                                     AiNewsReviewPolicy.Decision decision) {
        if (task == null) return null;
        if (!AiNewsReviewTaskStatus.NO_LONGER_REQUIRED.name().equals(task.getStatus())
                || !Objects.equals(task.getPolicyVersion(), AiNewsReviewPolicy.VERSION)
                || !Objects.equals(task.getRiskFingerprint(), decision.fingerprint())) {
            task.setStatus(AiNewsReviewTaskStatus.NO_LONGER_REQUIRED.name());
            task.setReasonsJson(writeReasons(List.of()));
            task.setPolicyVersion(AiNewsReviewPolicy.VERSION);
            task.setRiskFingerprint(decision.fingerprint());
            task.setRouteSource(ROUTE_SOURCE);
            task.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(task);
        }
        return task;
    }

    private AiNewsEventEntity findEvent(Long workspaceId, Long eventId) {
        AiNewsEventEntity event = eventMapper.selectOne(new LambdaQueryWrapper<AiNewsEventEntity>()
                .eq(AiNewsEventEntity::getWorkspaceId, workspace(workspaceId))
                .eq(AiNewsEventEntity::getId, eventId)
                .eq(AiNewsEventEntity::getDeleted, 0));
        if (event == null) throw new NewsClawException(404, "AI 动态事件不存在");
        return event;
    }

    private AiNewsEventEntity findEventForUpdate(Long workspaceId, Long eventId) {
        long ws = workspace(workspaceId);
        AiNewsEventEntity event = eventMapper.selectForUpdate(ws, eventId);
        return event != null ? event : findEvent(ws, eventId);
    }

    private AiNewsReviewTaskEntity findTask(long workspaceId, Long eventId) {
        if (eventId == null) return null;
        return taskMapper.selectOne(baseQuery(workspaceId)
                .eq(AiNewsReviewTaskEntity::getEventId, eventId));
    }

    private AiNewsReviewTaskEntity findTaskForUpdate(long workspaceId, Long eventId) {
        if (eventId == null) return null;
        AiNewsReviewTaskEntity task = taskMapper.selectForUpdate(workspaceId, eventId);
        return task != null ? task : findTask(workspaceId, eventId);
    }

    private static LambdaQueryWrapper<AiNewsReviewTaskEntity> baseQuery(long workspaceId) {
        return new LambdaQueryWrapper<AiNewsReviewTaskEntity>()
                .eq(AiNewsReviewTaskEntity::getWorkspaceId, workspaceId)
                .eq(AiNewsReviewTaskEntity::getDeleted, 0);
    }

    private void applyProjection(AiNewsEventEntity event, AiNewsReviewTaskEntity task) {
        if (event == null) return;
        if (task == null) {
            event.setReviewTaskId(null);
            event.setReviewStatus(null);
            event.setReviewRequired(false);
            event.setReviewReasons(List.of());
            return;
        }
        event.setReviewTaskId(task.getId());
        event.setReviewStatus(task.getStatus());
        event.setReviewRequired(AiNewsReviewTaskStatus.PENDING.name().equals(task.getStatus()));
        event.setReviewReasons(readReasons(task.getReasonsJson()));
    }

    private String writeReasons(List<String> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons == null ? List.of() : reasons);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize AI-news review reasons", e);
        }
    }

    private static String requiredNote(String note) {
        if (note == null || note.isBlank()) {
            throw new NewsClawException(400, "人工复核结论不能为空");
        }
        return trim(note, 2000, "");
    }

    private static String requiredOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new NewsClawException(400, "人工复核操作者不能为空");
        }
        return trim(operator, 256, "");
    }

    private static String trim(String value, int max, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static long workspace(Long workspaceId) {
        return workspaceId == null || workspaceId <= 0 ? DEFAULT_WORKSPACE : workspaceId;
    }
}

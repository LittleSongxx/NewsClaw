package vip.newsclaw.external.effect;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Idempotency ledger for calls that leave NewsClaw's transaction boundary.
 *
 * <p>The caller first claims a deterministic key, performs the network call
 * only after an acquired claim, then completes or fails that same owner token.
 * A crashed owner can be retried after the stale timeout. This does not claim
 * exactly-once delivery from external platforms; it prevents known duplicate
 * sends while preserving an auditable recovery path.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalEffectService {

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private static final int STALE_AFTER_SECONDS = 300;

    private final ExternalEffectMapper effectMapper;

    public ClaimResult claim(EffectRequest request) {
        validate(request);
        long workspaceId = request.workspaceId() == null || request.workspaceId() <= 0
                ? DEFAULT_WORKSPACE_ID : request.workspaceId();
        String ownerToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        ExternalEffectEntity existing = find(workspaceId, request.effectType(), request.idempotencyKey());
        if (existing == null) {
            ExternalEffectEntity created = newEffect(workspaceId, request, ownerToken, now);
            try {
                effectMapper.insert(created);
                return new ClaimResult(ClaimStatus.ACQUIRED, created, ownerToken);
            } catch (DuplicateKeyException duplicate) {
                existing = find(workspaceId, request.effectType(), request.idempotencyKey());
            } catch (Exception e) {
                // MySQL may wrap a duplicate key in a generic persistence exception.
                existing = find(workspaceId, request.effectType(), request.idempotencyKey());
                if (existing == null) {
                    throw e;
                }
            }
        }
        if (existing == null) {
            throw new IllegalStateException("Unable to create or load external effect claim");
        }
        if ("SUCCEEDED".equals(existing.getStatus())) {
            return new ClaimResult(ClaimStatus.ALREADY_SUCCEEDED, existing, null);
        }
        if ("IN_PROGRESS".equals(existing.getStatus()) && !isStale(existing, now)) {
            return new ClaimResult(ClaimStatus.IN_PROGRESS, existing, null);
        }

        boolean retry = existing.getAttemptCount() != null && existing.getAttemptCount() > 0;
        int updated = effectMapper.update(null, new LambdaUpdateWrapper<ExternalEffectEntity>()
                .eq(ExternalEffectEntity::getId, existing.getId())
                .eq(ExternalEffectEntity::getWorkspaceId, workspaceId)
                .eq(ExternalEffectEntity::getEffectType, request.effectType())
                .eq(ExternalEffectEntity::getIdempotencyKey, request.idempotencyKey())
                .in(ExternalEffectEntity::getStatus, "PENDING", "FAILED", "IN_PROGRESS")
                .set(ExternalEffectEntity::getStatus, "IN_PROGRESS")
                .set(ExternalEffectEntity::getOwnerToken, ownerToken)
                .set(ExternalEffectEntity::getStartedAt, now)
                .set(ExternalEffectEntity::getFinishedAt, null)
                .set(ExternalEffectEntity::getErrorMessage, null)
                .set(ExternalEffectEntity::getTarget, trim(request.target(), 512))
                .set(ExternalEffectEntity::getRequestDigest, trim(request.requestDigest(), 64))
                .set(ExternalEffectEntity::getRequestJson, trim(request.requestJson(), 32_000))
                .setSql("attempt_count = COALESCE(attempt_count, 0) + 1"));
        if (updated == 1) {
            ExternalEffectEntity claimed = findById(existing.getId());
            return new ClaimResult(retry ? ClaimStatus.RETRY_ACQUIRED : ClaimStatus.ACQUIRED,
                    claimed == null ? existing : claimed, ownerToken);
        }
        ExternalEffectEntity raced = find(workspaceId, request.effectType(), request.idempotencyKey());
        if (raced != null && "SUCCEEDED".equals(raced.getStatus())) {
            return new ClaimResult(ClaimStatus.ALREADY_SUCCEEDED, raced, null);
        }
        return new ClaimResult(ClaimStatus.IN_PROGRESS, raced == null ? existing : raced, null);
    }

    public void markSucceeded(ClaimResult claim, String target, String responseJson) {
        if (claim == null || !claim.acquired() || claim.effect() == null) {
            return;
        }
        try {
            effectMapper.update(null, completionUpdate(claim)
                    .set(ExternalEffectEntity::getStatus, "SUCCEEDED")
                    .set(ExternalEffectEntity::getTarget, trim(target, 512))
                    .set(ExternalEffectEntity::getResponseJson, trim(responseJson, 32_000))
                    .set(ExternalEffectEntity::getErrorMessage, null)
                    .set(ExternalEffectEntity::getFinishedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[ExternalEffect] Failed to mark {} as succeeded: {}", claim.effect().getId(), e.getMessage());
        }
    }

    public void markFailed(ClaimResult claim, Throwable error) {
        if (claim == null || !claim.acquired() || claim.effect() == null) {
            return;
        }
        try {
            effectMapper.update(null, completionUpdate(claim)
                    .set(ExternalEffectEntity::getStatus, "FAILED")
                    .set(ExternalEffectEntity::getErrorMessage,
                            trim(error == null ? "unknown external effect failure" : error.getMessage(), 1000))
                    .set(ExternalEffectEntity::getFinishedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[ExternalEffect] Failed to mark {} as failed: {}", claim.effect().getId(), e.getMessage());
        }
    }

    private LambdaUpdateWrapper<ExternalEffectEntity> completionUpdate(ClaimResult claim) {
        return new LambdaUpdateWrapper<ExternalEffectEntity>()
                .eq(ExternalEffectEntity::getId, claim.effect().getId())
                .eq(ExternalEffectEntity::getStatus, "IN_PROGRESS")
                .eq(ExternalEffectEntity::getOwnerToken, claim.ownerToken());
    }

    private ExternalEffectEntity newEffect(long workspaceId, EffectRequest request,
                                           String ownerToken, LocalDateTime now) {
        ExternalEffectEntity entity = new ExternalEffectEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setEffectType(trim(request.effectType(), 64));
        entity.setIdempotencyKey(trim(request.idempotencyKey(), 191));
        entity.setAggregateType(trim(request.aggregateType(), 64));
        entity.setAggregateId(trim(request.aggregateId(), 128));
        entity.setTarget(trim(request.target(), 512));
        entity.setRequestDigest(trim(request.requestDigest(), 64));
        entity.setRequestJson(trim(request.requestJson(), 32_000));
        entity.setStatus("IN_PROGRESS");
        entity.setAttemptCount(1);
        entity.setOwnerToken(ownerToken);
        entity.setStartedAt(now);
        entity.setDeleted(0);
        return entity;
    }

    private ExternalEffectEntity find(long workspaceId, String effectType, String idempotencyKey) {
        return effectMapper.selectOne(new LambdaQueryWrapper<ExternalEffectEntity>()
                .eq(ExternalEffectEntity::getWorkspaceId, workspaceId)
                .eq(ExternalEffectEntity::getEffectType, effectType)
                .eq(ExternalEffectEntity::getIdempotencyKey, idempotencyKey)
                .eq(ExternalEffectEntity::getDeleted, 0));
    }

    private ExternalEffectEntity findById(Long id) {
        return id == null ? null : effectMapper.selectById(id);
    }

    private static boolean isStale(ExternalEffectEntity effect, LocalDateTime now) {
        LocalDateTime startedAt = effect.getStartedAt();
        return startedAt == null || startedAt.isBefore(now.minusSeconds(STALE_AFTER_SECONDS));
    }

    private static void validate(EffectRequest request) {
        if (request == null || isBlank(request.effectType()) || isBlank(request.idempotencyKey())) {
            throw new IllegalArgumentException("effectType and idempotencyKey are required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public enum ClaimStatus {
        ACQUIRED,
        RETRY_ACQUIRED,
        ALREADY_SUCCEEDED,
        IN_PROGRESS
    }

    public record ClaimResult(ClaimStatus status, ExternalEffectEntity effect, String ownerToken) {
        public boolean acquired() {
            return status == ClaimStatus.ACQUIRED || status == ClaimStatus.RETRY_ACQUIRED;
        }
    }

    public record EffectRequest(
            Long workspaceId,
            String effectType,
            String idempotencyKey,
            String aggregateType,
            String aggregateId,
            String target,
            String requestDigest,
            String requestJson
    ) {
    }
}

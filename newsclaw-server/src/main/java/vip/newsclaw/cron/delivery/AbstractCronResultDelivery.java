package vip.newsclaw.cron.delivery;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import vip.newsclaw.channel.ChannelMessageRenderer;
import vip.newsclaw.cron.model.CronJobEntity;
import vip.newsclaw.dashboard.model.CronJobRunEntity;
import vip.newsclaw.dashboard.repository.CronJobRunMapper;
import vip.newsclaw.external.effect.ExternalEffectService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * RFC-063r §2.6.1: Template-Method base for {@link CronResultDelivery}.
 *
 * <p>Owns the cross-strategy invariants:
 * <ul>
 *   <li><b>Idempotency</b> — atomic SQL CAS on
 *       {@code mate_cron_job_run.delivery_status} via {@link #claimRun} so
 *       only one listener instance proceeds per run row even across cluster
 *       deployments (replaces RFC-063 v1's process-local Caffeine cache).</li>
 *   <li><b>State-machine bookkeeping</b> — {@link #markDelivered} on success,
 *       {@link #markNotDelivered} on failure (truncated message via Hutool
 *       {@code StrUtil.maxLength} per RFC §2.6.1).</li>
 *   <li><b>Render hook</b> — {@link #renderForChannel} delegates to the
 *       project's existing {@link ChannelMessageRenderer} so per-channel
 *       markdown / Block Kit rendering stays consistent with the inline reply
 *       path.</li>
 * </ul>
 *
 * <p>Concrete subclasses implement only
 * {@link #doDeliver(CronJobEntity, AssistantMessage, CronJobRunEntity)}.
 */
@Slf4j
public abstract class AbstractCronResultDelivery implements CronResultDelivery {

    /**
     * Default platform message-length cap used when the bound channel type
     * is unknown — matches Telegram's 4096 ceiling, the most permissive
     * among the small-cap platforms in {@link ChannelMessageRenderer#PLATFORM_LIMITS}.
     * RFC-063r §2.11 will refine this in PR-4 by passing per-channel limits
     * through {@code DeliveryOptions}.
     */
    private static final int DEFAULT_RENDER_MAX_LEN = 4096;

    private final CronJobRunMapper runMapper;
    /** Optional for source-compatible isolated delivery tests. */
    private final ExternalEffectService externalEffectService;

    protected AbstractCronResultDelivery(CronJobRunMapper runMapper) {
        this(runMapper, null);
    }

    protected AbstractCronResultDelivery(CronJobRunMapper runMapper,
                                         ExternalEffectService externalEffectService) {
        this.runMapper = runMapper;
        this.externalEffectService = externalEffectService;
    }

    @Override
    public final DeliveryOutcome deliver(CronJobEntity job, AssistantMessage result, CronJobRunEntity run) {
        if (!claimRun(run)) {
            return DeliveryOutcome.skipped("already-claimed-by-other-instance");
        }
        ExternalEffectService.ClaimResult effectClaim = claimExternalEffect(job, result, run);
        if (effectClaim != null && effectClaim.status() == ExternalEffectService.ClaimStatus.ALREADY_SUCCEEDED) {
            String target = effectClaim.effect() == null ? null : effectClaim.effect().getTarget();
            markDelivered(run, DeliveryOutcome.delivered(target));
            return DeliveryOutcome.skipped("external-effect-already-succeeded");
        }
        if (effectClaim != null && effectClaim.status() == ExternalEffectService.ClaimStatus.IN_PROGRESS) {
            // Do not transform an ambiguous in-flight delivery into a resend.
            // The stale-delivery sweep and effect-ledger retry path retain the
            // recovery signal without claiming a platform-level exactly-once guarantee.
            return DeliveryOutcome.skipped("external-effect-in-progress");
        }
        try {
            DeliveryOutcome outcome = doDeliver(job, result, run);
            if (effectClaim != null) {
                externalEffectService.markSucceeded(effectClaim, outcome.target(), null);
            }
            markDelivered(run, outcome);
            return outcome;
        } catch (Exception e) {
            if (effectClaim != null) {
                externalEffectService.markFailed(effectClaim, e);
            }
            markNotDelivered(run, e);
            throw e;
        }
    }

    /** Strategy's actual delivery work — invoked after CAS success. */
    protected abstract DeliveryOutcome doDeliver(
            CronJobEntity job, AssistantMessage result, CronJobRunEntity run);

    /**
     * RFC-063r §2.11: filter thinking + tool-call markers and join the
     * platform-truncated segments into a single string. Concrete strategies
     * call this before handing the result to the channel-specific send call.
     *
     * <p>Null-safe — null/empty {@link AssistantMessage} returns "" so
     * adapters never NPE.
     */
    protected String renderForChannel(AssistantMessage msg, Long channelId) {
        if (msg == null) return "";
        String text = msg.getText() != null ? msg.getText() : "";
        if (text.isEmpty()) return "";
        try {
            List<String> segments = ChannelMessageRenderer.renderForChannel(
                    text, /* filterThinking */ true, /* filterToolMessages */ true,
                    /* messageFormat */ null, DEFAULT_RENDER_MAX_LEN);
            return segments.isEmpty() ? "" : String.join("\n\n", segments);
        } catch (Exception e) {
            log.debug("[CronDelivery] renderForChannel failed (channelId={}); falling back to raw text: {}",
                    channelId, e.getMessage());
            return text;
        }
    }

    // ---------- SQL state-machine helpers ----------

    /**
     * Atomic SQL CAS: transition delivery_status from {@code NONE} or
     * {@code PENDING} → {@code PENDING}. Returns true iff this instance won
     * the race. NONE-eligibility lets fresh runs claim without a separate
     * "first-time" branch; PENDING-eligibility covers the rare same-instance
     * retry inside the listener.
     *
     * <p>SQL semantics gotcha: {@code IN (...)} never matches NULL. Legacy
     * rows from before V57 (pre-RFC) may have null delivery_status, so the
     * predicate explicitly tests {@code IS NULL OR IN (NONE, PENDING)} via
     * a nested OR group rather than putting null inside the IN list.
     */
    private boolean claimRun(CronJobRunEntity run) {
        return runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .and(w -> w.isNull(CronJobRunEntity::getDeliveryStatus)
                        .or().in(CronJobRunEntity::getDeliveryStatus, "NONE", "PENDING"))
                .set(CronJobRunEntity::getDeliveryStatus, "PENDING")) == 1;
    }

    private void markDelivered(CronJobRunEntity run, DeliveryOutcome o) {
        runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .set(CronJobRunEntity::getDeliveryStatus, "DELIVERED")
                .set(CronJobRunEntity::getDeliveryTarget, o.target()));
    }

    private void markNotDelivered(CronJobRunEntity run, Exception e) {
        runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .set(CronJobRunEntity::getDeliveryStatus, "NOT_DELIVERED")
                // Hutool's maxLength appends an ellipsis, which makes a 500
                // character input 503 characters and violates the database
                // VARCHAR(500) constraint. Persist a strict upper bound.
                .set(CronJobRunEntity::getDeliveryError, truncate(e.getMessage(), 500)));
    }

    private ExternalEffectService.ClaimResult claimExternalEffect(
            CronJobEntity job, AssistantMessage result, CronJobRunEntity run) {
        if (externalEffectService == null || run == null || run.getId() == null) {
            return null;
        }
        long workspaceId = job != null && job.getWorkspaceId() != null && job.getWorkspaceId() > 0
                ? job.getWorkspaceId() : 1L;
        Long channelId = job == null ? null : job.getChannelId();
        String effectKey = "cron-run:" + run.getId() + ":channel:" + (channelId == null ? "none" : channelId);
        String body = result == null || result.getText() == null ? "" : result.getText();
        return externalEffectService.claim(new ExternalEffectService.EffectRequest(
                workspaceId,
                "CRON_CHANNEL_DELIVERY",
                effectKey,
                "cron_job_run",
                String.valueOf(run.getId()),
                channelId == null ? null : String.valueOf(channelId),
                sha256(body),
                null));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return null;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}

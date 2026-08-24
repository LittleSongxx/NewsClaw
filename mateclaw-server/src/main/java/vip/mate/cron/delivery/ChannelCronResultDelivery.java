package vip.mate.cron.delivery;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.ChannelSessionStore;
import vip.mate.channel.DeliveryOptions;
import vip.mate.channel.model.ChannelSessionEntity;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.cron.model.DeliveryConfig;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;
import vip.mate.external.effect.ExternalEffectService;

import java.util.Map;
import java.util.Comparator;

/**
 * RFC-063r §2.6: deliver a cron job's assistant result back to its
 * originating IM channel via {@link ChannelManager#sendToChannel}.
 *
 * <p>{@link #supports} returns true only when both {@code channelId} and a
 * non-null {@code deliveryConfig.targetId()} are present — web-origin jobs
 * (no channelId) and partial bindings fall through and the run stays in
 * {@code delivery_status='NONE'}, matching the always-best-effort policy in
 * RFC §2.7.3.
 */
@Component
@Order(10)
public class ChannelCronResultDelivery extends AbstractCronResultDelivery {

    private final ChannelManager channelManager;
    /** May be null only for the source-compatible unit-test constructor. */
    private final ChannelSessionStore channelSessionStore;

    /** Spring constructor with the live session fallback. */
    @Autowired
    public ChannelCronResultDelivery(CronJobRunMapper runMapper,
                                     ChannelManager channelManager,
                                     ChannelSessionStore channelSessionStore,
                                     ExternalEffectService externalEffectService) {
        super(runMapper, externalEffectService);
        this.channelManager = channelManager;
        this.channelSessionStore = channelSessionStore;
    }

    /** Existing three-argument construction remains available to embedders/tests. */
    public ChannelCronResultDelivery(CronJobRunMapper runMapper,
                                     ChannelManager channelManager,
                                     ChannelSessionStore channelSessionStore) {
        this(runMapper, channelManager, channelSessionStore, null);
    }

    /** Keeps older isolated tests and embedders source-compatible. */
    public ChannelCronResultDelivery(CronJobRunMapper runMapper,
                                     ChannelManager channelManager) {
        this(runMapper, channelManager, null);
    }

    @Override
    public boolean supports(CronJobEntity job) {
        if (job == null || job.getChannelId() == null) return false;
        DeliveryConfig dc = job.getDeliveryConfig();
        if (hasTarget(dc)) return true;
        return latestSession(job.getChannelId()) != null;
    }

    @Override
    protected DeliveryOutcome doDeliver(CronJobEntity job, AssistantMessage result, CronJobRunEntity run) {
        DeliveryConfig dc = job.getDeliveryConfig();
        ChannelSessionEntity fallback = hasTarget(dc) ? null : latestSession(job.getChannelId());
        String targetId = hasTarget(dc) ? dc.targetId() : fallback != null ? fallback.getTargetId() : null;
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalStateException("channel delivery target is not available yet");
        }
        String rendered = renderForChannel(result, job.getChannelId());
        // RFC-063r §2.10: forward thread / account hints via DeliveryOptions.
        // Adapters that don't override the 4-arg proactiveSend default ignore
        // the hints — preserves pre-RFC behavior for non-threading platforms.
        DeliveryOptions options = new DeliveryOptions(
                dc != null ? dc.threadId() : null,
                dc != null ? dc.accountId() : null,
                Map.of());
        channelManager.sendToChannel(job.getChannelId(), targetId, rendered, options);
        return DeliveryOutcome.delivered(targetId);
    }

    private boolean hasTarget(DeliveryConfig dc) {
        return dc != null && dc.targetId() != null && !dc.targetId().isBlank();
    }

    private ChannelSessionEntity latestSession(Long channelId) {
        if (channelSessionStore == null || channelId == null) return null;
        return channelSessionStore.listByChannelId(channelId).stream()
                .filter(session -> session.getTargetId() != null && !session.getTargetId().isBlank())
                .max(Comparator.comparing(session -> session.getLastActiveTime() == null
                        ? java.time.LocalDateTime.MIN : session.getLastActiveTime()))
                .orElse(null);
    }
}

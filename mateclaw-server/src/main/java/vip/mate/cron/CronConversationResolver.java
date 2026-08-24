package vip.mate.cron;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.ChannelSessionStore;
import vip.mate.channel.model.ChannelSessionEntity;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.cron.model.DeliveryConfig;

import java.util.List;
import java.util.Optional;
import java.util.Comparator;

/**
 * Single source of truth for the {@code conversationId} a cron run writes to.
 * <p>
 * Cron used to write every run to a per-job orphan conversation
 * ({@code "cron_" + job.getId()}). Those rows existed in {@code mate_conversation}
 * but had no entry in any sidebar — the user had no way to reach them. The
 * delivery pipeline ({@code CronResultDelivery}) covered the IM case (push
 * back to DingTalk / Feishu / etc.) but Web-origin cron jobs ended up with
 * {@code delivery_status='NONE'} and silent results.
 * <p>
 * The new policy:
 * <ul>
 *   <li>Web-origin cron (no {@code channelId}) → {@code "tasks_" + workspaceId}.
 *       A single workspace-scoped conversation pre-seeded as "📋 定时任务"
 *       (V65 migration). All Web cron output lands here so the user has one
 *       reliable place to look.</li>
 *   <li>IM-bound cron with an existing channel session that matches the
 *       delivery target → the session's conversationId. This makes the cron
 *       output appear inline in the IM mirror conversation — when the user
 *       opens the channel in Web Console, they see cron history alongside
 *       chat history, no separate inbox to check.</li>
 *   <li>IM-bound cron without a matching session yet (e.g. first run before
 *       the user has interacted with the channel) → fall back to the old
 *       per-job {@code cron_<id>} conversation so push delivery still works
 *       and the run isn't lost.</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CronConversationResolver {

    private final ChannelSessionStore channelSessionStore;

    public String resolve(CronJobEntity job) {
        if (job == null) return "tasks_1";

        // IM-bound cron: try to thread output into the existing channel session
        // so the IM mirror in Web Console shows it inline with regular chat.
        if (job.getChannelId() != null) {
            String sessionConvId = findChannelSessionConvId(job);
            if (sessionConvId != null) return sessionConvId;
            // No session yet — keep the legacy per-job conversation so push
            // delivery to the IM still works and the run isn't dropped.
            return "cron_" + job.getId();
        }

        // Web-origin cron: unified per-workspace tasks conversation.
        Long ws = job.getWorkspaceId() != null ? job.getWorkspaceId() : 1L;
        return "tasks_" + ws;
    }

    /**
     * Find the existing channel session for the cron's creator. Match
     * priority — most specific first:
     * <ol>
     *   <li>{@code (channelId, dc.userId)} — the senderId of who created
     *       this cron, captured by {@code CronJobTool.propagateChannelBinding}.
     *       This is the stable identifier across replyToken rotations.</li>
     *   <li>{@code (channelId, dc.targetId)} — fallback for legacy rows that
     *       were written before the {@code userId} field was added (V62
     *       baseline / older). Matches when the channel adapter happens to
     *       use the same value for {@code session.targetId} and
     *       {@code dc.targetId} (Slack / Discord / Telegram). Will miss for
     *       DingTalk-style replyToken adapters but those rows will never
     *       have written a useful targetId match either, so behavior is
     *       no worse than before.</li>
     *   <li>If both miss, return null and fall back to {@code cron_<id>}.</li>
     * </ol>
     */
    private String findChannelSessionConvId(CronJobEntity job) {
        DeliveryConfig dc = job.getDeliveryConfig();
        try {
            List<ChannelSessionEntity> sessions = channelSessionStore.listByChannelId(job.getChannelId());
            if (sessions.isEmpty()) return null;

            // A seeded vertical cron can know its channel before the operator
            // has sent the first message, so delivery_config is initially
            // empty. Once a session exists, use the most recently active one
            // as the deterministic single-workspace target. Later seed runs
            // persist this target and the creator-id matching path takes over.
            if (dc == null) {
                return sessions.stream()
                        .filter(s -> s.getConversationId() != null && !s.getConversationId().isBlank())
                        .max(Comparator.comparing(s -> s.getLastActiveTime() == null
                                ? java.time.LocalDateTime.MIN : s.getLastActiveTime()))
                        .map(ChannelSessionEntity::getConversationId)
                        .orElse(null);
            }

            // Preferred: match by creator's senderId (V63+ rows).
            if (dc.userId() != null && !dc.userId().isBlank()) {
                String byUser = sessions.stream()
                        .filter(s -> dc.userId().equals(s.getSenderId()))
                        .map(ChannelSessionEntity::getConversationId)
                        .findFirst()
                        .orElse(null);
                if (byUser != null) return byUser;
            }

            // Fallback: legacy targetId match (works for non-replyToken adapters).
            if (dc.targetId() != null && !dc.targetId().isBlank()) {
                return sessions.stream()
                        .filter(s -> dc.targetId().equals(s.getTargetId()))
                        .map(ChannelSessionEntity::getConversationId)
                        .findFirst()
                        .orElse(null);
            }
            // Legacy rows may carry a channel binding but no target details.
            // The same recent-session fallback keeps them recoverable after a
            // restart without changing the persisted job contract.
            return sessions.stream()
                    .filter(s -> s.getConversationId() != null && !s.getConversationId().isBlank())
                    .max(Comparator.comparing(s -> s.getLastActiveTime() == null
                            ? java.time.LocalDateTime.MIN : s.getLastActiveTime()))
                    .map(ChannelSessionEntity::getConversationId)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[CronConvResolver] session lookup failed for job {}: {}",
                    job.getId(), e.getMessage());
            return null;
        }
    }

    /** Reused by header insertion to know whether we are in the unified tasks view. */
    public boolean isWebOriginTasksConv(String conversationId) {
        return conversationId != null && conversationId.startsWith("tasks_");
    }
}

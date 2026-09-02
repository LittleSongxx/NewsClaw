package vip.newsclaw.trigger.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import vip.newsclaw.trigger.dispatch.TriggerDispatcher;
import vip.newsclaw.trigger.model.TriggerEntity;
import vip.newsclaw.trigger.repository.TriggerMapper;
import vip.newsclaw.config.EnvironmentConfig;
import vip.newsclaw.news.service.AiNewsCandidatePipelineProperties;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/**
 * Maintains the in-memory map of cron-pattern triggers active on this node
 * and fires them through {@link TriggerDispatcher}. Coordination across
 * nodes uses ShedLock (per-trigger lock keyed by id) so simultaneous fires
 * collapse into one. Each scheduled task captures the trigger's
 * {@code patternVersion} at register time; on fire the live row's version
 * is re-read and the local task self-cancels when it has fallen behind a
 * newer cron expression — no need to chase a stale {@link ScheduledFuture}.
 *
 * <p>Only the {@code cron} pattern type registers here. Other pattern
 * flavours (channel_message, workflow_completion, ...) drive triggers
 * through their own ingestion pipeline and do not occupy a scheduler tick.
 */
@Slf4j
@Component
public class TriggerScheduler {

    private static final String PATTERN_CRON = "cron";

    private final TriggerMapper triggerMapper;
    private final TriggerDispatcher dispatcher;
    private final LockProvider lockProvider;
    private final ObjectMapper objectMapper;

    /**
     * A workflow may legitimately spend more than a minute in an LLM/tool
     * call. Keep the distributed fire lease longer than that by default, but
     * leave an operator knob for deployments with a known upper bound.
     */
    @Value("${newsclaw.workflow.trigger.fire-lock-at-most-seconds:7200}")
    private long fireLockAtMostSeconds = 7200L;

    /** Spring-bound flag is preferred; the environment fallback keeps small
     * unit-test constructions deterministic. */
    @Autowired(required = false)
    private AiNewsCandidatePipelineProperties candidatePipelineProperties;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<Long, Registration> registrations = new ConcurrentHashMap<>();
    private ThreadPoolExecutor fireExecutor;

    public TriggerScheduler(TriggerMapper triggerMapper,
                            TriggerDispatcher dispatcher,
                            LockProvider lockProvider,
                            ObjectMapper objectMapper) {
        this.triggerMapper = triggerMapper;
        this.dispatcher = dispatcher;
        this.lockProvider = lockProvider;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initScheduler() {
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("trigger-tick-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        fireExecutor = new ThreadPoolExecutor(
                8, 8, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128),
                r -> {
                    Thread t = new Thread(r, "trigger-fire");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdownScheduler() {
        scheduler.shutdown();
        if (fireExecutor != null) fireExecutor.shutdownNow();
        registrations.clear();
    }

    /** Boot-time registration sweep; runs after Flyway and bean wiring complete. */
    @EventListener(ApplicationReadyEvent.class)
    void registerEnabledTriggersOnStartup() {
        syncFromDatabase();
    }

    /**
     * Periodic sweep that converges this node's local registrations with
     * the canonical state in {@code mate_trigger}.
     *
     * <p>Reasons this exists:
     * <ul>
     *   <li>Multi-instance: when node A creates / updates / disables a
     *       cron trigger, node B never gets the local-only register call.
     *       The fire-time {@code patternVersion} guard self-cancels stale
     *       schedules but does NOT register newly-created or newly-enabled
     *       triggers — only this sweep does.</li>
     *   <li>Recovery from missed events: if a register / unregister call
     *       races with a node restart, the in-memory map can drift from
     *       the row state. Refreshing every minute caps the divergence.</li>
     * </ul>
     *
     * <p>Convergence rules:
     * <ul>
     *   <li>Row enabled + cron type + not registered locally → register.</li>
     *   <li>Row enabled but local {@code capturedVersion} differs from
     *       row's {@code pattern_version} → re-register (the schedule
     *       carries the new expression).</li>
     *   <li>Local registration exists for a row that's now disabled,
     *       deleted, or no longer cron-typed → unregister.</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${newsclaw.workflow.trigger.sync-interval-ms:60000}",
               initialDelayString = "${newsclaw.workflow.trigger.sync-initial-delay-ms:60000}")
    public void syncFromDatabase() {
        var enabled = triggerMapper.selectList(new LambdaQueryWrapper<TriggerEntity>()
                .eq(TriggerEntity::getEnabled, true)
                .eq(TriggerEntity::getDeleted, 0));
        java.util.Set<Long> seenIds = new java.util.HashSet<>();
        int registered = 0, refreshed = 0, removed = 0;
        for (TriggerEntity t : enabled) {
            if (!PATTERN_CRON.equalsIgnoreCase(t.getPatternType())) continue;
            if (isBlockedDailyRadar(t)) {
                if (t.getId() != null) unregister(t.getId());
                continue;
            }
            seenIds.add(t.getId());
            Registration current = registrations.get(t.getId());
            long liveVersion = t.getPatternVersion() == null ? 1L : t.getPatternVersion();
            if (current == null) {
                if (register(t)) registered++;
            } else if (current.capturedVersion != liveVersion) {
                if (register(t)) refreshed++;
            }
        }
        // Drop registrations whose row was disabled / deleted / changed type
        // since the last sweep. Snapshot the keys first to avoid concurrent
        // modification on the underlying map.
        for (Long localId : new java.util.ArrayList<>(registrations.keySet())) {
            if (!seenIds.contains(localId)) {
                unregister(localId);
                removed++;
            }
        }
        if (registered + refreshed + removed > 0) {
            log.info("[TriggerScheduler] sync: registered={} refreshed={} removed={} active={}",
                    registered, refreshed, removed, registrations.size());
        }
    }

    /** Register or replace a single trigger (called from {@code TriggerService} on save). */
    public boolean register(TriggerEntity trigger) {
        if (trigger == null || !Integer.valueOf(0).equals(trigger.getDeleted())
                || !PATTERN_CRON.equalsIgnoreCase(trigger.getPatternType())) {
            if (trigger != null && trigger.getId() != null) unregister(trigger.getId());
            return false;
        }
        if (isBlockedDailyRadar(trigger)) {
            if (trigger.getId() != null) unregister(trigger.getId());
            log.info("[TriggerScheduler] daily AI-news radar remains unregistered while candidate pipeline is disabled");
            return false;
        }
        try {
            return registerInternal(trigger);
        } catch (RuntimeException invalidSchedule) {
            if (trigger.getId() != null) unregister(trigger.getId());
            log.warn("[TriggerScheduler] trigger {} schedule rejected: {}",
                    trigger.getId(), invalidSchedule.getMessage());
            return false;
        }
    }

    /** Cancel any active schedule for {@code triggerId}. Idempotent. */
    public void unregister(long triggerId) {
        Registration r = registrations.remove(triggerId);
        if (r != null) {
            r.future.cancel(false);
        }
    }

    /**
     * Whether {@code triggerId} currently occupies an active scheduled task on
     * this node. Visible because monitoring / health endpoints surface the
     * same fact, and the alternative would be exposing the raw registration
     * map.
     */
    public boolean isRegistered(long triggerId) {
        return registrations.containsKey(triggerId);
    }

    /**
     * Manually drive the lamport + dispatch path the cron tick would otherwise
     * call. Used by integration tests; production code should never call this
     * directly — the scheduler owns its own tick.
     */
    public void fireForTest(long triggerId, long capturedVersion) {
        fireWithCoordination(triggerId, capturedVersion);
    }

    private boolean registerInternal(TriggerEntity trigger) {
        unregister(trigger.getId());
        ParsedCron parsed = parseCron(trigger);
        if (parsed == null) return false;

        long capturedVersion = trigger.getPatternVersion() == null ? 1L : trigger.getPatternVersion();
        Runnable task = () -> submitFire(trigger.getId(), capturedVersion);
        ScheduledFuture<?> future = scheduler.schedule(task,
                new CronTrigger(parsed.expression, parsed.timeZone));
        registrations.put(trigger.getId(), new Registration(future, capturedVersion));
        log.info("[TriggerScheduler] Registered trigger {} cron='{}' tz={} version={}",
                trigger.getId(), parsed.expression, parsed.timeZone.getID(), capturedVersion);
        return true;
    }

    private void fireWithCoordination(long triggerId, long capturedVersion) {
        // Per-fire lamport check: a newer expression in the DB invalidates
        // this scheduled task. Drop the fire and unregister so the next
        // registration cycle picks up the new schedule.
        TriggerEntity live = loadFireable(triggerId, capturedVersion);
        if (live == null) return;

        // Cross-node coordination: at-most-one node fires per tick.
        Optional<SimpleLock> lock = lockProvider.lock(new LockConfiguration(
                Instant.now(),
                "trigger-fire-" + triggerId,
                Duration.ofSeconds(Math.max(60L, fireLockAtMostSeconds)),
                Duration.ofSeconds(5)));
        if (lock.isEmpty()) {
            return; // peer is firing
        }
        boolean fireClaimed = false;
        boolean bounded = live.getMaxFires() != null && live.getMaxFires() > 0;
        try {
            // A disable/delete/edit may commit after the optimistic read but
            // before this node acquires the cross-node lock. Re-read under
            // ownership so that stale snapshot cannot dispatch.
            live = loadFireable(triggerId, capturedVersion);
            if (live == null) return;
            // An unlimited trigger has no quota to reserve.  Keep the direct
            // outcome update for that common path (and for older mapper
            // fixtures); bounded triggers reserve a slot so max_fires remains
            // race-safe across nodes.
            if (bounded && triggerMapper.claimFire(triggerId, LocalDateTime.now()) != 1) {
                log.info("[TriggerScheduler] trigger {} exhausted before dispatch", triggerId);
                unregister(triggerId);
                return;
            }
            fireClaimed = bounded;
            vip.newsclaw.trigger.dispatch.DispatchResult outcome =
                    dispatcher.dispatch(live, Map.of("firedAt", Instant.now().toString()));
            // Bookkeeping is honest: only a real fire bumps fireCount /
            // lastFiredAt. Persist only those columns so this callback cannot
            // overwrite an administrator's newer trigger snapshot.
            LocalDateTime now = LocalDateTime.now();
            String error = outcome != null && outcome.fired()
                    ? null : outcome == null ? "dispatcher returned null" : outcome.reason();
            if (fireClaimed) {
                triggerMapper.settleClaimedFire(triggerId,
                        outcome != null && outcome.fired() ? 1 : 0, error, now);
            } else {
                triggerMapper.recordDispatchOutcome(triggerId,
                        outcome != null && outcome.fired() ? 1 : 0, error, now);
            }
        } catch (Exception e) {
            log.error("[TriggerScheduler] trigger {} fire failed: {}", triggerId, e.getMessage(), e);
            if (fireClaimed) {
                try {
                    triggerMapper.settleClaimedFire(triggerId, 0,
                            "scheduler threw: " + e.getMessage(), LocalDateTime.now());
                } catch (Exception ignored) {
                    // Best-effort — don't let a bookkeeping failure mask the dispatch failure.
                }
            } else {
                try {
                    triggerMapper.recordDispatchOutcome(triggerId, 0,
                            "scheduler threw: " + e.getMessage(), LocalDateTime.now());
                } catch (Exception ignored) {
                    // Best-effort — don't let a bookkeeping failure mask the dispatch failure.
                }
            }
        } finally {
            try {
                lock.get().unlock();
            } catch (Exception unlockEx) {
                log.warn("[TriggerScheduler] trigger {} lock release failed: {}",
                        triggerId, unlockEx.getMessage());
            }
        }
    }

    private void submitFire(long triggerId, long capturedVersion) {
        try {
            fireExecutor.execute(() -> fireWithCoordination(triggerId, capturedVersion));
        } catch (RejectedExecutionException busy) {
            log.warn("[TriggerScheduler] trigger {} fire queue full; tick skipped", triggerId);
        }
    }

    private TriggerEntity loadFireable(long triggerId, long capturedVersion) {
        TriggerEntity live = triggerMapper.selectById(triggerId);
        if (live == null || !Boolean.TRUE.equals(live.getEnabled())
                || !Integer.valueOf(0).equals(live.getDeleted())
                || isBlockedDailyRadar(live)) {
            unregister(triggerId);
            return null;
        }
        long liveVersion = live.getPatternVersion() == null ? 1L : live.getPatternVersion();
        if (liveVersion != capturedVersion) {
            log.info("[TriggerScheduler] trigger {} self-cancelling (version changed {} -> {})",
                    triggerId, capturedVersion, liveVersion);
            unregister(triggerId);
            return null;
        }
        if (live.getMaxFires() != null && live.getMaxFires() > 0
                && live.getFireCount() != null && live.getFireCount() >= live.getMaxFires()) {
            log.info("[TriggerScheduler] trigger {} reached max_fires={}, unregistering",
                    triggerId, live.getMaxFires());
            unregister(triggerId);
            return null;
        }
        return live;
    }

    private record ParsedCron(String expression, TimeZone timeZone) {}

    private ParsedCron parseCron(TriggerEntity trigger) {
        try {
            JsonNode node = objectMapper.readTree(
                    trigger.getPatternJson() == null ? "{}" : trigger.getPatternJson());
            String expr = node.path("cron").asText("");
            if (expr.isBlank()) {
                log.warn("[TriggerScheduler] trigger {} missing 'cron' in pattern_json; skipping",
                        trigger.getId());
                return null;
            }
            String tz = node.path("timezone").asText("UTC");
            return new ParsedCron(expr, TimeZone.getTimeZone(ZoneId.of(tz)));
        } catch (Exception e) {
            log.warn("[TriggerScheduler] trigger {} pattern_json parse failed: {}",
                    trigger.getId(), e.getMessage());
            return null;
        }
    }

    private boolean isBlockedDailyRadar(TriggerEntity trigger) {
        return trigger != null
                && ("ai-news.template.v1.daily-radar".equals(trigger.getName())
                || hasManagedDailyRadarMarker(trigger.getPatternJson()))
                && (!EnvironmentConfig.aiNewsRadarEnabled() || !candidatePipelineEnabled());
    }

    private boolean hasManagedDailyRadarMarker(String patternJson) {
        if (patternJson == null || patternJson.isBlank()) return false;
        try {
            return EnvironmentConfig.AI_NEWS_DAILY_RADAR_MANAGED_KEY.equals(
                    objectMapper.readTree(patternJson).path("managedKey").asText(null));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean candidatePipelineEnabled() {
        return candidatePipelineProperties != null
                ? candidatePipelineProperties.isEnabled()
                : EnvironmentConfig.aiNewsCandidatePipelineEnabled();
    }

    private record Registration(ScheduledFuture<?> future, long capturedVersion) {}
}

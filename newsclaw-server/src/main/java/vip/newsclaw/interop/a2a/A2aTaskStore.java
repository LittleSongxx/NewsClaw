package vip.newsclaw.interop.a2a;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

public class A2aTaskStore {

    private final int maxTasks;
    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentMap<String, A2aTask> tasks = new ConcurrentHashMap<>();
    private record RpcSnapshot(Map<String, Object> value, Instant updatedAt) {}

    private final ConcurrentMap<String, RpcSnapshot> rpcSnapshots = new ConcurrentHashMap<>();

    public A2aTaskStore(int maxTasks, Duration ttl) {
        this(maxTasks, ttl, Clock.systemUTC());
    }

    public A2aTaskStore(int maxTasks, Duration ttl, Clock clock) {
        this.maxTasks = Math.max(1, maxTasks);
        this.ttl = ttl == null ? Duration.ofHours(1) : ttl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public synchronized boolean putIfAbsent(String tenant, A2aTask task) {
        if (task == null || task.id() == null || task.id().isBlank()) {
            return false;
        }
        if (tasks.size() >= maxTasks) {
            sweepExpired();
            if (tasks.size() >= maxTasks) {
                // A non-blocking request can leave a task non-terminal forever
                // if its bridge disappears. Evict stale active rows only under
                // admission pressure so normal long-running tasks keep working.
                evictStaleTasks(clock.instant().minus(ttl));
            }
            if (tasks.size() >= maxTasks) {
                throw new IllegalStateException("too many A2A tasks");
            }
        }
        return tasks.putIfAbsent(taskKey(tenant, task.id()), task.withUpdatedAt(clock.instant())) == null;
    }

    public Optional<A2aTask> get(String tenant, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskKey(tenant, taskId)));
    }

    public Optional<A2aTask> update(String tenant, String taskId, UnaryOperator<A2aTask> updater) {
        String key = taskKey(tenant, taskId);
        A2aTask updated = tasks.computeIfPresent(key,
                (ignored, current) -> updater.apply(current).withUpdatedAt(clock.instant()));
        return Optional.ofNullable(updated);
    }

    public synchronized boolean rememberRpcSnapshot(String tenant, String rpcId, Map<String, Object> snapshot) {
        if (rpcId == null || snapshot == null) {
            return true;
        }
        sweepRpcSnapshots();
        return rpcSnapshots.putIfAbsent(rpcKey(tenant, rpcId),
                new RpcSnapshot(Map.copyOf(snapshot), clock.instant())) == null;
    }

    public Optional<Map<String, Object>> rpcSnapshot(String tenant, String rpcId) {
        if (rpcId == null) {
            return Optional.empty();
        }
        String key = rpcKey(tenant, rpcId);
        RpcSnapshot snapshot = rpcSnapshots.get(key);
        if (snapshot == null) return Optional.empty();
        if (snapshot.updatedAt().isBefore(clock.instant().minus(ttl))) {
            rpcSnapshots.remove(key, snapshot);
            return Optional.empty();
        }
        return Optional.of(snapshot.value());
    }

    public int sweepExpired() {
        Instant cutoff = clock.instant().minus(ttl);
        int removed = 0;
        Iterator<Map.Entry<String, A2aTask>> it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, A2aTask> entry = it.next();
            A2aTask task = entry.getValue();
            if (task.terminal() && task.updatedAt().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        sweepRpcSnapshots(cutoff);
        return removed;
    }

    private void sweepRpcSnapshots() {
        sweepRpcSnapshots(clock.instant().minus(ttl));
    }

    private void sweepRpcSnapshots(Instant cutoff) {
        rpcSnapshots.entrySet().removeIf(entry -> entry.getValue().updatedAt().isBefore(cutoff));
        // Keep replay de-duplication bounded even when callers use a unique RPC
        // id for every request. The task cap is the existing memory budget.
        while (rpcSnapshots.size() > maxTasks) {
            rpcSnapshots.entrySet().stream()
                    .min(Map.Entry.comparingByValue(java.util.Comparator.comparing(RpcSnapshot::updatedAt)))
                    .ifPresent(oldest -> rpcSnapshots.remove(oldest.getKey(), oldest.getValue()));
        }
    }

    private void evictStaleTasks(Instant cutoff) {
        tasks.entrySet().removeIf(entry -> entry.getValue().updatedAt().isBefore(cutoff));
    }

    private static String taskKey(String tenant, String taskId) {
        return normalizeTenant(tenant) + "|" + taskId;
    }

    private static String rpcKey(String tenant, String rpcId) {
        return normalizeTenant(tenant) + "|rpc|" + rpcId;
    }

    private static String normalizeTenant(String tenant) {
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }
}

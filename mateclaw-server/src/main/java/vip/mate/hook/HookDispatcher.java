package vip.mate.hook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.hook.action.HookContext;
import vip.mate.hook.action.HookResult;
import vip.mate.hook.event.MateHookEvent;
import vip.mate.hook.model.HookRunEntity;
import vip.mate.hook.repository.HookRunMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hook 派发器：监听 {@link MateHookEvent}，按 registry 匹配 hook，并发执行 action，全程硬预算保护。
 *
 * <p><b>关键性能设计</b>：
 * <ul>
 *   <li>虚拟线程执行器：IO-bound action 并发数无硬上限，但整体受 {@link Semaphore} 保护</li>
 *   <li>{@code vt.invokeAll(tasks, deadline, ms)}：单事件硬 5s 预算；超时未完成的 action 自动被 cancel</li>
 *   <li>失败完全吞到 log.warn：主调用链零影响</li>
 *   <li>{@link HookRateLimiter}：双层（global QPS + per-hook 每分钟）</li>
 *   <li>审计写库 <b>仅用虚拟线程异步</b>，不阻塞派发</li>
 * </ul>
 * 之所以不用 JDK 21 {@code StructuredTaskScope}：后者在 JDK 21 仍是 preview API，
 * 需要 {@code --enable-preview} 编译/运行标志；{@link ExecutorService#invokeAll(java.util.Collection, long, TimeUnit)}
 * 是稳定 API，语义等价（超时后未完成的任务被自动 cancel）。</p>
 *
 * <p>当 {@code mateclaw.hooks.enabled=false} 时 {@link #onEvent} 立即返回，开销仅一次布尔判断。</p>
 */
@Slf4j
@Component
public class HookDispatcher {

    private final HookRegistry registry;
    private final HookRunMapper runMapper;
    private final HookProperties props;

    private final HookRateLimiter rateLimiter;
    private final Semaphore concurrencySemaphore;
    private final ExecutorService vt;
    private final ExecutorService auditVt;
    private final AtomicLong dispatchCount = new AtomicLong();

    public HookDispatcher(HookRegistry registry, HookRunMapper runMapper, HookProperties props) {
        this.registry = registry;
        this.runMapper = runMapper;
        this.props = props;
        this.rateLimiter = new HookRateLimiter(props.getGlobalRateLimit());
        this.concurrencySemaphore = new Semaphore(props.getGlobalConcurrency());
        this.vt = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("hook-dispatch-", 0).factory());
        this.auditVt = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("hook-audit-", 0).factory());
    }

    @EventListener
    public void onEvent(MateHookEvent event) {
        if (!props.isEnabled()) return;
        List<HookMatch> matches = registry.match(event.type());
        if (matches.isEmpty()) return;

        // 不阻塞 publisher 线程：整个派发扔进虚拟线程
        vt.execute(() -> dispatchAll(event, matches));
    }

    private void dispatchAll(MateHookEvent event, List<HookMatch> matches) {
        // 优先短等待拿 semaphore（虚拟线程 park 开销极低），减少事件丢失；
        // 1s 仍拿不到说明后端严重堵塞，此时才丢弃并 warn 让运维感知
        boolean acquired;
        try {
            acquired = concurrencySemaphore.tryAcquire(1, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            log.warn("hook dispatch dropped (concurrency semaphore full for >1s); event={}", event.type());
            return;
        }
        try {
            List<Callable<Void>> tasks = new ArrayList<>(matches.size());
            for (HookMatch m : matches) {
                if (!rateLimiter.tryAcquire(m.entity().getId(), m.entity().getRateLimitPerMin())) {
                    recordRunAsync(m.entity().getId(), event.type(), HookResult.rateLimited());
                    continue;
                }
                tasks.add(() -> { executeOne(m, event); return null; });
            }
            if (tasks.isEmpty()) return;

            // invokeAll 以硬 deadline 执行；超时仍未完成的任务会被 cancel（虚拟线程支持中断）
            try {
                vt.invokeAll(tasks, props.getDispatchDeadline().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            log.warn("hook dispatch failure for event {}: {}", event.type(), e.getMessage());
        } finally {
            concurrencySemaphore.release();
            dispatchCount.incrementAndGet();
        }
    }

    private HookResult executeOne(HookMatch m, MateHookEvent event) {
        long start = System.nanoTime();
        var ctx = new HookContext(
                m.entity().getId(),
                m.entity().getName(),
                Map.of("event.type", event.type()));
        HookResult result;
        try {
            result = m.action().execute(event, ctx);
        } catch (Throwable t) {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            log.warn("hook action threw for hook={}, event={}: {}",
                    m.entity().getName(), event.type(), t.getMessage());
            result = HookResult.failed(t.getClass().getSimpleName() + ": " + t.getMessage(), ms);
        }
        recordRunAsync(m.entity().getId(), event.type(), result);
        return result;
    }

    private void recordRunAsync(Long hookId, String eventType, HookResult result) {
        if (!props.getAudit().isEnabled()) return;
        auditVt.execute(() -> {
            try {
                HookRunEntity row = new HookRunEntity();
                row.setHookId(hookId);
                row.setEventType(eventType);
                row.setStatus(result.status().name());
                row.setDurationMs((int) Math.min(result.durationMs(), Integer.MAX_VALUE));
                row.setMessage(truncate(result.message(), 510));
                row.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()));
                runMapper.insert(row);
            } catch (Exception e) {
                // 审计失败不影响主链
                log.debug("hook audit write failed: {}", e.getMessage());
            }
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 观察用：已派发的事件累计数。 */
    public long dispatchCount() { return dispatchCount.get(); }

    /** 显式关闭（测试或优雅停机时）。 */
    public void shutdown(long timeoutSec) {
        vt.shutdown();
        auditVt.shutdown();
        try {
            vt.awaitTermination(timeoutSec, TimeUnit.SECONDS);
            auditVt.awaitTermination(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

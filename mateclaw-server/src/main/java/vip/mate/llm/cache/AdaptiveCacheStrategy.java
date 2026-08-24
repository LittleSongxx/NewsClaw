package vip.mate.llm.cache;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自适应装饰器：包裹任一基础策略，根据真实命中率动态降级。
 *
 * <p>规则：
 * <ul>
 *   <li>启动后默认走 delegate 策略</li>
 *   <li>{@link #recordMiss()} / {@link #recordHit()} 由 usage 聚合层在解析响应后回调</li>
 *   <li>连续 {@code missThreshold} 次 miss → 临时切到 {@link NoOpCacheStrategy}，
 *       持续 {@code coolDownMillis} 毫秒后自动恢复</li>
 *   <li>任意一次 hit 立即清零计数并恢复</li>
 * </ul></p>
 *
 * <p>这是有状态对象，建议作为 Spring 单例 bean。所有计数用原子类型，无锁、零分配热路径。</p>
 */
@Slf4j
public final class AdaptiveCacheStrategy implements PromptCacheStrategy {

    private final PromptCacheStrategy delegate;
    private final int missThreshold;
    private final long coolDownMillis;

    private final AtomicInteger consecutiveMisses = new AtomicInteger();
    private final AtomicLong degradedUntilEpochMs = new AtomicLong();

    public AdaptiveCacheStrategy(PromptCacheStrategy delegate, int missThreshold, long coolDownMillis) {
        this.delegate = (delegate instanceof AdaptiveCacheStrategy)
                ? ((AdaptiveCacheStrategy) delegate).delegate
                : delegate;
        this.missThreshold = Math.max(1, missThreshold);
        this.coolDownMillis = Math.max(0, coolDownMillis);
    }

    @Override
    public CachedPlan plan(CachePlanContext ctx) {
        return isDegraded() ? CachedPlan.none() : delegate.plan(ctx);
    }

    @Override
    public boolean shouldCache(CachePlanContext ctx) {
        return !isDegraded() && delegate.shouldCache(ctx);
    }

    /** 命中：清零并立刻恢复（如已降级）。 */
    public void recordHit() {
        consecutiveMisses.set(0);
        degradedUntilEpochMs.set(0);
    }

    /** 未命中：累加；越过阈值则进入冷却期。 */
    public void recordMiss() {
        int n = consecutiveMisses.incrementAndGet();
        if (n >= missThreshold) {
            long until = System.currentTimeMillis() + coolDownMillis;
            degradedUntilEpochMs.set(until);
            log.warn("Prompt cache strategy degraded after {} consecutive misses; coolDown={}ms",
                    n, coolDownMillis);
        }
    }

    private boolean isDegraded() {
        long until = degradedUntilEpochMs.get();
        if (until == 0) return false;
        if (System.currentTimeMillis() >= until) {
            // 冷却期已过：原子清零（CAS 防止并发覆写）
            degradedUntilEpochMs.compareAndSet(until, 0);
            consecutiveMisses.set(0);
            return false;
        }
        return true;
    }
}

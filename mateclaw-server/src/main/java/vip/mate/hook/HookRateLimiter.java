package vip.mate.hook;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简的按 hook 分桶的"每分钟令牌数"限流器。
 *
 * <p>用 Caffeine 60 秒 TTL 过期的 counter，命中一次 +1；超过 {@code limitPerMinute} 即拒绝。
 * 不做严格令牌桶（精确但慢），只求 "order of magnitude" 保护：对 hook 场景足够。</p>
 *
 * <p>另外提供全局计数器做全局限流，用于防止海量事件下 CPU 饱和。</p>
 *
 * <p>读写都是 O(1)，无锁（AtomicInteger + Caffeine 内部分段锁）。</p>
 */
public final class HookRateLimiter {

    private final Cache<Long, AtomicInteger> perHookBuckets;
    private final AtomicInteger globalCounter = new AtomicInteger();
    private final AtomicInteger globalLimitPerSec;
    private volatile long globalWindowStartMs;

    public HookRateLimiter(int globalLimitPerSec) {
        this.globalLimitPerSec = new AtomicInteger(globalLimitPerSec);
        this.perHookBuckets = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(10_000)    // hook 总数上限
                .build();
        this.globalWindowStartMs = System.currentTimeMillis();
    }

    /**
     * @return true 表示允许本次执行，false 表示被限流
     */
    public boolean tryAcquire(long hookId, int limitPerMinute) {
        // 1. 全局闸门：每秒 N 次
        if (!tryGlobal()) return false;

        // 2. per-hook 闸门：1 分钟 N 次
        AtomicInteger bucket = perHookBuckets.get(hookId, k -> new AtomicInteger());
        int effectiveLimit = (limitPerMinute <= 0) ? 60 : limitPerMinute;
        return bucket.incrementAndGet() <= effectiveLimit;
    }

    private boolean tryGlobal() {
        long now = System.currentTimeMillis();
        long windowStart = this.globalWindowStartMs;
        if (now - windowStart >= 1000L) {
            // 滚动 1 秒窗口，重置
            this.globalWindowStartMs = now;
            globalCounter.set(0);
        }
        return globalCounter.incrementAndGet() <= globalLimitPerSec.get();
    }

    /** 动态调整全局速率（通常无需）。 */
    public void setGlobalLimitPerSec(int v) {
        this.globalLimitPerSec.set(Math.max(1, v));
    }
}

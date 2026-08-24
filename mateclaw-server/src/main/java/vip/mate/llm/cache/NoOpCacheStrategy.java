package vip.mate.llm.cache;

/**
 * 占位策略：永不打缓存断点。
 *
 * <p>用于 {@link AdaptiveCacheStrategy} 在连续 cache miss 后降级，
 * 或在 {@code mateclaw.llm.cache.enabled=false} 时全局短路。</p>
 */
public final class NoOpCacheStrategy implements PromptCacheStrategy {

    public static final NoOpCacheStrategy INSTANCE = new NoOpCacheStrategy();

    private NoOpCacheStrategy() {}

    @Override
    public CachedPlan plan(CachePlanContext ctx) {
        return CachedPlan.none();
    }

    @Override
    public boolean shouldCache(CachePlanContext ctx) {
        return false;
    }
}

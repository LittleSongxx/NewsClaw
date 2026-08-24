package vip.mate.llm.cache;

/**
 * Prompt 缓存策略 SPI。
 *
 * <p>所有策略实现都被 sealed 锁定，便于 JDK 21 模式匹配 switch 穷尽分支、
 * 也避免运行期反射式插件加载带来的不可预期成本。</p>
 *
 * <p>调用契约：
 * <ol>
 *   <li>装饰层先调 {@link #shouldCache(CachePlanContext)} 判定是否值得缓存</li>
 *   <li>若 true，调 {@link #plan(CachePlanContext)} 拿到 {@link CachedPlan}</li>
 *   <li>由协议特化的序列化器把断点写到具体请求体</li>
 * </ol></p>
 */
public sealed interface PromptCacheStrategy
        permits SystemAndTailCacheStrategy, NoOpCacheStrategy, AdaptiveCacheStrategy {

    CachedPlan plan(CachePlanContext ctx);

    default boolean shouldCache(CachePlanContext ctx) {
        if (!ctx.protocolSupportsCacheControl()) {
            return false;
        }
        // 短对话直接跳过：cache write 比常规调用贵 25%，单次性请求会倒亏
        return ctx.totalPromptTokens() >= ctx.minPromptTokens() && ctx.turnCount() >= 2;
    }
}

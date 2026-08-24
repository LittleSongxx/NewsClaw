package vip.mate.llm.cache;

/**
 * 把 {@link CachedPlan} 转换为协议特化的 {@link CacheDirectives}。
 *
 * <p>sealed 锁定可选实现，避免反射式插件加载并支持穷尽 switch。</p>
 *
 * <p>注意：本接口只决定"要打哪些断点 + 需要哪些 HTTP header"，不直接修改底层 HTTP body。
 * 真正写 JSON 的工作由调用层完成；这样保证序列化器是纯函数，便于单元测试。</p>
 *
 * <p><b>Anthropic 例外</b>：spring-ai 1.1.4+ 已通过 {@code AnthropicCacheOptions} 提供一等支持，
 * 由 {@link AnthropicCacheOptionsFactory} 直接装配到 {@code AnthropicChatOptions}，不走本接口。
 * 本接口仅用于我们自有客户端（如 OpenAI Responses）需要手动装配缓存指令的协议。</p>
 */
public sealed interface CacheSerializer
        permits OpenAIResponsesCacheSerializer {

    /** 此序列化器服务的协议；调度方据此挑选实现。 */
    vip.mate.llm.model.ModelProtocol protocol();

    /**
     * @param plan   策略产出的方案（可能为空 → 返回 {@link CacheDirectives#empty} ）
     * @param ctx    与策略相同的上下文，便于序列化器看 messages 大小决定是否缩减断点
     * @return       供 HTTP 拦截器消费的指令
     */
    CacheDirectives serialize(CachedPlan plan, CachePlanContext ctx);
}

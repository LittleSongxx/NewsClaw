package vip.mate.llm.cache;

/**
 * Anthropic prompt cache 的存活时间。
 * <p>{@link #EXTENDED_1H} 需要 beta header {@code anthropic-beta: extended-cache-ttl-2025-04-11}，
 * 可通过 application.yml 的 {@code mateclaw.llm.cache.ttl=extended-1h} 启用。</p>
 */
public enum CacheTtl {
    DEFAULT_5M,
    EXTENDED_1H
}

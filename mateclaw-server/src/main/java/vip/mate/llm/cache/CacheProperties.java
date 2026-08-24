package vip.mate.llm.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prompt cache 总配置。
 *
 * <pre>
 * mateclaw:
 *   llm:
 *     cache:
 *       enabled: true
 *       min-prompt-tokens: 1024
 *       max-breakpoints: 4
 *       ttl: default              # default | extended-1h
 *       include-tools-block: true
 *       adaptive:
 *         enabled: true
 *         miss-threshold: 5
 *         cool-down: 60s
 * </pre>
 */
@ConfigurationProperties(prefix = "mateclaw.llm.cache")
public class CacheProperties {

    /** 总开关；false 时全部走 NoOp。 */
    private boolean enabled = true;

    /** 累计 prompt token 低于此值则跳过缓存（避免 cache write 倒亏）。 */
    private int minPromptTokens = 1024;

    /** 单请求最多打几个 cache_control 断点（Anthropic 上限 4）。 */
    private int maxBreakpoints = 4;

    /** 默认 TTL；{@code extended-1h} 需要 Anthropic beta header。 */
    private Ttl ttl = Ttl.DEFAULT;

    /** 是否把工具 schema 段也作为一个断点（独立断点，避免与 system 共享）。 */
    private boolean includeToolsBlock = true;

    private final Adaptive adaptive = new Adaptive();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMinPromptTokens() { return minPromptTokens; }
    public void setMinPromptTokens(int minPromptTokens) { this.minPromptTokens = minPromptTokens; }

    public int getMaxBreakpoints() { return maxBreakpoints; }
    public void setMaxBreakpoints(int maxBreakpoints) { this.maxBreakpoints = maxBreakpoints; }

    public Ttl getTtl() { return ttl; }
    public void setTtl(Ttl ttl) { this.ttl = ttl; }

    public boolean isIncludeToolsBlock() { return includeToolsBlock; }
    public void setIncludeToolsBlock(boolean includeToolsBlock) { this.includeToolsBlock = includeToolsBlock; }

    public Adaptive getAdaptive() { return adaptive; }

    public CacheTtl resolveCacheTtl() {
        return ttl == Ttl.EXTENDED_1H ? CacheTtl.EXTENDED_1H : CacheTtl.DEFAULT_5M;
    }

    public enum Ttl { DEFAULT, EXTENDED_1H }

    public static class Adaptive {
        private boolean enabled = true;
        private int missThreshold = 5;
        /** 字符串解析在 application.yml 由 Spring Boot 自动转换；默认 60_000 ms。 */
        private long coolDownMs = 60_000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMissThreshold() { return missThreshold; }
        public void setMissThreshold(int missThreshold) { this.missThreshold = missThreshold; }
        public long getCoolDownMs() { return coolDownMs; }
        public void setCoolDownMs(long coolDownMs) { this.coolDownMs = coolDownMs; }
    }
}

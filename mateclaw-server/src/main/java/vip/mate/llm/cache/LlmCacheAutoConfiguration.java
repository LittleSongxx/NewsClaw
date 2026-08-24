package vip.mate.llm.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RFC-014 prompt cache 装配。
 *
 * <p>暴露 {@link PromptCacheStrategy} 作为 Spring bean：当 {@code mateclaw.llm.cache.adaptive.enabled=true}
 * 时用 {@link AdaptiveCacheStrategy} 包装基础策略 {@link SystemAndTailCacheStrategy}，
 * 后者负责实际断点计算；自适应层根据 cache miss 率自动降级。</p>
 *
 * <p>{@link AnthropicCacheOptionsFactory} 由 {@code @Component} 自动注册。</p>
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class LlmCacheAutoConfiguration {

    @Bean
    public PromptCacheStrategy promptCacheStrategy(CacheProperties props) {
        if (!props.isEnabled()) {
            return NoOpCacheStrategy.INSTANCE;
        }
        PromptCacheStrategy base = new SystemAndTailCacheStrategy();
        if (props.getAdaptive().isEnabled()) {
            return new AdaptiveCacheStrategy(
                    base,
                    props.getAdaptive().getMissThreshold(),
                    props.getAdaptive().getCoolDownMs());
        }
        return base;
    }
}

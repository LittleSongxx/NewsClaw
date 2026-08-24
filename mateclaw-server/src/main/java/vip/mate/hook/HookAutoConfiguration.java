package vip.mate.hook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RFC-017 Hook 系统装配入口。
 *
 * <p>{@link HookRegistry}、{@link HookDispatcher}、{@link HookActionFactory} 都是
 * {@code @Component} 自动发现；本 AutoConfiguration 仅负责启用 {@link HookProperties}。</p>
 */
@Configuration
@EnableConfigurationProperties(HookProperties.class)
public class HookAutoConfiguration {
}

package vip.mate.memory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆模块自动配置
 *
 * @author MateClaw Team
 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryAutoConfiguration {
}

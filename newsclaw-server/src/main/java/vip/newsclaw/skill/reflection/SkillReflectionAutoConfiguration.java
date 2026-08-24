package vip.newsclaw.skill.reflection;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers configuration for the out-of-band skill reflection service.
 *
 * @author NewsClaw Team
 */
@Configuration
@EnableConfigurationProperties(SkillReflectionProperties.class)
public class SkillReflectionAutoConfiguration {
}

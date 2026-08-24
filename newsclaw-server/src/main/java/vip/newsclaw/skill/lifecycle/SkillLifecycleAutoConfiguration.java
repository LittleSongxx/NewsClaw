package vip.newsclaw.skill.lifecycle;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the skill lifecycle curator.
 *
 * @author NewsClaw Team
 */
@Configuration
@EnableConfigurationProperties(SkillLifecycleProperties.class)
public class SkillLifecycleAutoConfiguration {
}

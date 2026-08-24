package vip.newsclaw.skill.routine;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers configuration for routine mining.
 *
 * @author NewsClaw Team
 */
@Configuration
@EnableConfigurationProperties(SkillRoutineProperties.class)
public class SkillRoutineAutoConfiguration {
}

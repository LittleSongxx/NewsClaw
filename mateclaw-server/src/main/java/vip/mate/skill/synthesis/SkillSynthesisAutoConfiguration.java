package vip.mate.skill.synthesis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RFC-023: 自动 Skill 合成的配置注册
 */
@Configuration
@EnableConfigurationProperties(SkillSynthesisProperties.class)
public class SkillSynthesisAutoConfiguration {
}

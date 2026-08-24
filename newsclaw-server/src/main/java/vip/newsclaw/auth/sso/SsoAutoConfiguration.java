package vip.newsclaw.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import vip.newsclaw.auth.sso.provider.FeishuSsoProvider;

/**
 * SSO 配置。启用 {@link SsoProperties} 绑定 + 按需注册飞书 Provider。
 * <p>
 * 仅当 {@code newsclaw.sso.enabled=true} 时此配置生效。飞书 Provider 进一步要求
 * {@code newsclaw.sso.feishu.enabled=true}。
 *
 * @author NewsClaw Team
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SsoProperties.class)
@ConditionalOnProperty(name = "newsclaw.sso.enabled", havingValue = "true")
public class SsoAutoConfiguration {

    /**
     * 飞书 SSO Provider。仅当飞书 SSO 启用时注册。
     */
    @Bean
    @ConditionalOnProperty(name = "newsclaw.sso.feishu.enabled", havingValue = "true")
    public FeishuSsoProvider feishuSsoProvider(SsoProperties ssoProperties, ObjectMapper objectMapper) {
        return new FeishuSsoProvider(ssoProperties.getFeishu(), objectMapper);
    }
}

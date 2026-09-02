package vip.newsclaw.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vip.newsclaw.kbopen.auth.KbScopeInterceptor;

import java.util.Arrays;

/**
 * Web MVC 配置（跨域、拦截器等）
 *
 * @author NewsClaw Team
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({GraphObservationProperties.class, ConversationWindowProperties.class, ToolTimeoutProperties.class,
        ReasoningRetentionProperties.class})
public class WebMvcConfig implements WebMvcConfigurer {

    private final WorkspaceAccessInterceptor workspaceAccessInterceptor;
    private final KbScopeInterceptor kbScopeInterceptor;

    /** CORS allowed origins, comma-separated. Empty means same-origin only. */
    @Value("${newsclaw.cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(workspaceAccessInterceptor)
                .addPathPatterns("/api/**");
        // KB Open API scope+ownership checks (A1). Runs after KbOpenApiAuthFilter
        // injected the KbApiKeyContext into the request attributes.
        registry.addInterceptor(kbScopeInterceptor)
                .addPathPatterns("/api/v1/open/kb/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins == null ? new String[0] : allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty() && !"*".equals(origin))
                .toArray(String[]::new);
        if (origins.length == 0) return;
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * Expose per-skill bundled assets (logos, screenshots, ...) at
     * {@code /skill-assets/<skillName>/...}.
     *
     * <p>Source layout: {@code src/main/resources/skills/<name>/assets/<file>}.
     * Built-in skills can ship icons / hero images alongside SKILL.md without
     * polluting {@code src/main/resources/static/} (gitignored — that path is
     * the newsclaw-ui Vite build output).
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/showcase/**")
                .addResourceLocations("classpath:/static/showcase/")
                .setCachePeriod(86400);
        registry.addResourceHandler("/skill-assets/**")
                .addResourceLocations("classpath:/skills/")
                .setCachePeriod(86400);
    }
}

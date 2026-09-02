package vip.newsclaw.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Fail-closed checks for a deployment that is marked as production. */
@Slf4j
@Component
@org.springframework.core.annotation.Order(Ordered.LOWEST_PRECEDENCE)
public class SecurityStartupValidator implements ApplicationRunner {

    /** Hash shipped by legacy seed files; a production DB must never retain it. */
    static final String LEGACY_BOOTSTRAP_HASH =
            "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2";
    private static final List<String> PRODUCTION_PROFILES =
            List.of("postgres", "mysql", "kingbase", "prod", "production");

    @Autowired
    private Environment environment;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Value("${newsclaw.security.production-mode:false}")
    private boolean productionMode;

    @Value("${newsclaw.jwt.secret:}")
    private String jwtSecret;

    @Value("${newsclaw.datasource.encrypt-key:}")
    private String encryptKey;

    @Value("${newsclaw.setting.key:}")
    private String settingKey;

    @Value("${spring.datasource.username:}")
    private String databaseUsername;

    @Value("${spring.datasource.password:}")
    private String databasePassword;

    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    @Value("${newsclaw.cors.allowed-origins:}")
    private String corsOrigins;

    @Value("${newsclaw.setup.allow-anonymous:true}")
    private boolean anonymousSetup;

    @Value("${newsclaw.setup.await-language-selection:false}")
    private boolean awaitLanguageSelection;

    @Value("${newsclaw.openapi.expose-ui:false}")
    private boolean openApiExposed;

    @Value("${newsclaw.tools.screenshot.enabled:false}")
    private boolean screenshotEnabled;

    @Value("${newsclaw.tools.external-publish.enabled:false}")
    private boolean externalPublishEnabled;

    @Value("${newsclaw.webchat.public-enabled:false}")
    private boolean webChatEnabled;

    @Value("${newsclaw.server.public-base-url:}")
    private String publicBaseUrl;

    @Override
    public void run(ApplicationArguments args) {
        if (!isProduction()) {
            log.info("[Security] Non-production profile; production fail-closed checks are disabled.");
            return;
        }

        List<String> failures = new ArrayList<>();
        String[] active = environment == null ? new String[0] : environment.getActiveProfiles();
        if (Arrays.stream(active).noneMatch(SecurityStartupValidator::isProductionProfile)) {
            failures.add("production mode requires an explicit postgres/mysql/kingbase profile");
        }
        requireSecret(jwtSecret, "JWT_SECRET", failures);
        requireSecret(encryptKey, "NEWSCLAW_ENCRYPT_KEY", failures);
        requireSecret(settingKey, "NEWSCLAW_SETTING_KEY", failures);
        if (isBlank(databaseUsername) || isKnownDatabaseDefault(databaseUsername)) {
            failures.add("database username is missing or a known default");
        }
        if (isBlank(databasePassword) || isKnownDatabaseDefault(databasePassword)) {
            failures.add("database password is missing or a known default");
        }
        if (h2ConsoleEnabled) failures.add("H2 console must be disabled");
        if (anonymousSetup) failures.add("anonymous setup must be disabled");
        if (awaitLanguageSelection) failures.add("desktop language-selection bootstrap cannot run in production");
        if (openApiExposed) failures.add("OpenAPI UI must not be public");
        if (screenshotEnabled) failures.add("screenshot tool must remain disabled on the public profile");
        if (externalPublishEnabled) failures.add("external publishing tools must remain disabled on the public profile");
        if (webChatEnabled) failures.add("public WebChat must remain disabled on the public profile");
        if (externalPublishEnabled && isBlank(publicBaseUrl)) {
            failures.add("external delivery requires an explicit HTTPS public base URL");
        }
        validateCors(failures);
        if (!isBlank(publicBaseUrl)) validateHttps(publicBaseUrl, "public base URL", failures);
        rejectLegacyAdmin(failures);

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Production security validation failed: " + String.join("; ", failures));
        }
        log.info("[Security] Production fail-closed checks passed (profile={}, CORS origins={}).",
                String.join(",", active), corsOrigins == null || corsOrigins.isBlank() ? 0 : corsOrigins.split(",").length);
    }

    private boolean isProduction() {
        if (productionMode) return true;
        return environment != null && Arrays.stream(environment.getActiveProfiles()).anyMatch(SecurityStartupValidator::isProductionProfile);
    }

    private static boolean isProductionProfile(String profile) {
        return profile != null && PRODUCTION_PROFILES.contains(profile.trim().toLowerCase(Locale.ROOT));
    }

    private static void requireSecret(String value, String name, List<String> failures) {
        if (isBlank(value)) {
            failures.add(name + " is missing");
            return;
        }
        String secret = value.trim();
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        String lower = secret.toLowerCase(Locale.ROOT);
        if (bytes.length < 32 || isLowEntropy(secret)
                || lower.contains("replace") || lower.contains("change-me")
                || lower.contains("please-change") || lower.contains("default")
                || lower.contains("example") || lower.contains("newsclaw@2024")) {
            failures.add(name + " must be a high-entropy secret of at least 32 bytes");
        }
    }

    private static boolean isLowEntropy(String value) {
        if (value.chars().distinct().count() < 12) return true;
        return value.chars().allMatch(c -> c == value.charAt(0));
    }

    private void validateCors(List<String> failures) {
        if (isBlank(corsOrigins)) return; // same-origin static showcase needs no CORS.
        for (String raw : corsOrigins.split(",")) {
            String origin = raw.trim();
            if (origin.isEmpty()) continue;
            if ("*".equals(origin)) {
                failures.add("CORS wildcard cannot be used with credentials");
                continue;
            }
            validateHttps(origin, "CORS origin", failures);
        }
    }

    private static void validateHttps(String value, String label, List<String> failures) {
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                failures.add(label + " must be an https origin without query/fragment");
            }
        } catch (Exception e) {
            failures.add(label + " is not a valid https origin");
        }
    }

    private void rejectLegacyAdmin(List<String> failures) {
        if (jdbcTemplate == null) return;
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM mate_user WHERE username='admin' AND password=? AND deleted=0",
                    Integer.class, LEGACY_BOOTSTRAP_HASH);
            if (count != null && count > 0) {
                failures.add("legacy seeded administrator credential is still active; rotate it before startup");
            }
        } catch (Exception e) {
            // Flyway/bootstrap failures surface through their own startup error; do not print SQL details.
            log.debug("[Security] Could not inspect seeded administrator row: {}", e.getClass().getSimpleName());
        }
    }

    private static boolean isKnownDatabaseDefault(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return List.of("postgres", "root", "system", "newsclaw123", "admin2026@123",
                "change-me-strong-user-password", "change-me-strong-admin-password").contains(v);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package vip.newsclaw.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityStartupValidatorTest {

    @Test
    void productionRequiresRealSecretsAndNoLegacyAdmin() {
        SecurityStartupValidator validator = configured();
        assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments()));

        ReflectionTestUtils.setField(validator, "jwtSecret", "replace-with-a-long-random-jwt-secret-0123456789");
        assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments()));
    }

    @Test
    void wildcardCorsIsRejectedInProduction() {
        SecurityStartupValidator validator = configured();
        ReflectionTestUtils.setField(validator, "corsOrigins", "*");
        assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments()));
    }

    private static SecurityStartupValidator configured() {
        SecurityStartupValidator validator = new SecurityStartupValidator();
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"postgres"});
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Integer.class), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);
        ReflectionTestUtils.setField(validator, "environment", environment);
        ReflectionTestUtils.setField(validator, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(validator, "productionMode", true);
        ReflectionTestUtils.setField(validator, "jwtSecret", "jwt-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEF");
        ReflectionTestUtils.setField(validator, "encryptKey", "encrypt-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEF");
        ReflectionTestUtils.setField(validator, "settingKey", "setting-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEF");
        ReflectionTestUtils.setField(validator, "databaseUsername", "newsclaw_app");
        ReflectionTestUtils.setField(validator, "databasePassword", "db-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEF");
        ReflectionTestUtils.setField(validator, "anonymousSetup", false);
        ReflectionTestUtils.setField(validator, "openApiExposed", false);
        ReflectionTestUtils.setField(validator, "screenshotEnabled", false);
        ReflectionTestUtils.setField(validator, "externalPublishEnabled", false);
        ReflectionTestUtils.setField(validator, "webChatEnabled", false);
        return validator;
    }
}

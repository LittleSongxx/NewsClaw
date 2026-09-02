package vip.newsclaw.skill.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillScriptEnvironmentPolicyTest {

    @Test
    void onlyRuntimeAllowlistSurvivesBeforeSkillSecretsAreInjected() {
        Map<String, String> env = new HashMap<>(Map.of(
                "PATH", "/usr/bin",
                "LANG", "C.UTF-8",
                "OPENAI_API_KEY", "server-secret",
                "DB_PASSWORD", "database-secret"));

        SkillScriptExecutionService.retainSafeInheritedEnvironment(env);

        assertEquals(Map.of("PATH", "/usr/bin", "LANG", "C.UTF-8"), env);
    }

    @Test
    void authenticatedProxyIsRemovedButPlainProxyMaySurvive() {
        Map<String, String> env = new HashMap<>(Map.of(
                "PATH", "/usr/bin",
                "HTTP_PROXY", "http://proxy.example:8080",
                "HTTPS_PROXY", "https://user:secret@proxy.example:8443",
                "NO_PROXY", "localhost,127.0.0.1"));

        SkillScriptExecutionService.retainSafeInheritedEnvironment(env);

        assertEquals("http://proxy.example:8080", env.get("HTTP_PROXY"));
        assertFalse(env.containsKey("HTTPS_PROXY"));
        assertEquals("localhost,127.0.0.1", env.get("NO_PROXY"));
    }

    @Test
    void proxyParserRejectsCredentialsAndMalformedValues() {
        assertTrue(SkillScriptExecutionService.isUnauthenticatedProxy("http://proxy.example:8080"));
        assertFalse(SkillScriptExecutionService.isUnauthenticatedProxy("http://u:p@proxy.example:8080"));
        assertFalse(SkillScriptExecutionService.isUnauthenticatedProxy("not-a-url"));
    }
}

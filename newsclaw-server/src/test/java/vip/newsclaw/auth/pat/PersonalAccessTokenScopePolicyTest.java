package vip.newsclaw.auth.pat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PersonalAccessTokenScopePolicyTest {

    @Test
    void scopesAuthorizeResourceAndOperation() {
        assertTrue(PersonalAccessTokenScopePolicy.allows("chat:write", "POST", "/api/v1/chat/stream"));
        assertFalse(PersonalAccessTokenScopePolicy.allows("chat:read", "POST", "/api/v1/chat/stream"));
        assertTrue(PersonalAccessTokenScopePolicy.allows("settings:*", "PUT", "/api/v1/settings"));
        assertFalse(PersonalAccessTokenScopePolicy.allows("settings:read", "PUT", "/api/v1/settings"));
        assertTrue(PersonalAccessTokenScopePolicy.allows(null, "DELETE", "/api/v1/plugins/x"));
    }

    @Test
    void normalizationRejectsUnknownScopeGrammar() {
        assertEquals("chat:read,chat:write", PersonalAccessTokenScopePolicy.normalize(
                " chat:write, CHAT:READ "));
        assertThrows(IllegalArgumentException.class,
                () -> PersonalAccessTokenScopePolicy.normalize("anything-goes"));
    }
}

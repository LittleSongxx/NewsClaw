package vip.newsclaw.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.newsclaw.auth.model.UserEntity;
import vip.newsclaw.auth.pat.PersonalAccessTokenEntity;
import vip.newsclaw.auth.pat.PersonalAccessTokenService;
import vip.newsclaw.auth.service.AuthService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthFilterScopeTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void restrictedPatIsRejectedBeforeController() throws Exception {
        AuthService auth = mock(AuthService.class);
        PersonalAccessTokenService pats = mock(PersonalAccessTokenService.class);
        JwtAuthFilter filter = new JwtAuthFilter(auth, pats);
        PersonalAccessTokenEntity pat = pat("settings:read");
        when(pats.findActiveByPlaintext("mc_token")).thenReturn(Optional.of(pat));
        when(auth.findById(7L)).thenReturn(user());
        MockHttpServletRequest request = request("PUT", "/api/v1/settings");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void queryTokenIsLimitedToExplicitStreamingAndWebSocketPaths() {
        assertTrue(JwtAuthFilter.allowsQueryToken("/api/v1/chat/stream"));
        assertTrue(JwtAuthFilter.allowsQueryToken("/api/v1/talk/ws"));
        assertTrue(JwtAuthFilter.allowsQueryToken("/api/v1/wiki/research/stream/s1"));
        assertFalse(JwtAuthFilter.allowsQueryToken("/api/v1/settings"));
        assertFalse(JwtAuthFilter.allowsQueryToken("/api/v1/operational-data/download"));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader("Authorization", "Bearer mc_token");
        return request;
    }

    private static PersonalAccessTokenEntity pat(String scopes) {
        PersonalAccessTokenEntity pat = new PersonalAccessTokenEntity();
        pat.setUserId(7L);
        pat.setScopes(scopes);
        return pat;
    }

    private static UserEntity user() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setUsername("alice");
        user.setRole("user");
        user.setEnabled(true);
        return user;
    }
}

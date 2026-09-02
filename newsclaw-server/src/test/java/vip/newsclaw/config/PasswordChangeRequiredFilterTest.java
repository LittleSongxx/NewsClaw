package vip.newsclaw.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.newsclaw.auth.service.AuthService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PasswordChangeRequiredFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bootstrapAdminCanOnlyCallPasswordChangeApi() throws Exception {
        AuthService authService = mock(AuthService.class);
        when(authService.mustChangePassword("admin")).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, java.util.List.of()));
        PasswordChangeRequiredFilter filter = new PasswordChangeRequiredFilter(authService);
        MockHttpServletResponse blocked = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/settings"), blocked,
                (request, response) -> fail("blocked request reached controller"));
        assertEquals(403, blocked.getStatus());

        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("PUT", "/api/v1/auth/users/1/password"), allowed,
                (request, response) -> ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
        assertEquals(204, allowed.getStatus());
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}

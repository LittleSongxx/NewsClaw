package vip.newsclaw.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoginRateLimitFilterTest {

    @Test
    void forwardedHeaderIsIgnoredFromUntrustedPeer() throws Exception {
        LoginRateLimitFilter filter = filter("");
        MockHttpServletResponse last = null;
        for (int i = 0; i < 6; i++) {
            MockHttpServletRequest request = login("10.0.0.1", "198.51.100." + i);
            last = new MockHttpServletResponse();
            MockHttpServletResponse response = last;
            filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401));
        }
        assertEquals(429, last.getStatus());
    }

    @Test
    void trustedProxyMaySupplyClientAddress() throws Exception {
        LoginRateLimitFilter filter = filter("10.0.0.1");
        for (int i = 0; i < 6; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(login("10.0.0.1", "198.51.100." + i), response,
                    (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401));
            assertEquals(401, response.getStatus());
        }
    }

    @Test
    void successfulLoginClearsTheBucket() throws Exception {
        LoginRateLimitFilter filter = filter("");
        for (int i = 0; i < 8; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(login("10.0.0.1", null), response, (req, res) -> { });
            assertEquals(200, response.getStatus());
        }
    }

    private static LoginRateLimitFilter filter(String trusted) {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        ReflectionTestUtils.setField(filter, "trustedProxies", trusted);
        return filter;
    }

    private static MockHttpServletRequest login(String remote, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(remote);
        if (xff != null) request.addHeader("X-Forwarded-For", xff);
        return request;
    }
}

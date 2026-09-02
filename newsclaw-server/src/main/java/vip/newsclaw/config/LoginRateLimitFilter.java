package vip.newsclaw.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import vip.newsclaw.common.net.SsrfAllowlist;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.Arrays;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiter for login + SSO bind endpoints — prevents brute force attacks.
 * Allows max 5 attempts per IP per minute across all password-checking paths.
 *
 * @author NewsClaw Team
 */
@Slf4j
@Component
public class LoginRateLimitFilter implements Filter {

    @Value("${newsclaw.security.trusted-proxies:}")
    private String trustedProxies;

    private static final int MAX_ATTEMPTS = 5;
    /**
     * Endpoints that accept a username + password and must be rate-limited
     * against brute force. SSO callback is excluded (no password submitted).
     * <p>
     * The counter is keyed by client IP only (not IP + path), so 5 failed
     * password attempts on /auth/login also locks /auth/sso/bind for the same
     * IP within the window. This is intentional: all entries share the same
     * brute-force surface, and a normal user who fat-fingers their password
     * 5 times is unlikely to immediately need SSO bind. If finer isolation is
     * needed later, switch the cache key to {@code ip + ":" + path}.
     */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/sso/bind");

    /** IP → attempt count, auto-expires after 1 minute */
    private final Cache<String, AtomicInteger> attempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;

        boolean protectedRequest = "POST".equalsIgnoreCase(httpReq.getMethod())
                && PROTECTED_PATHS.contains(httpReq.getRequestURI());
        String ip = null;
        if (protectedRequest) {
            ip = getClientIp(httpReq);
            AtomicInteger count = attempts.get(ip, k -> new AtomicInteger(0));
            int current = count.incrementAndGet();

            if (current > MAX_ATTEMPTS) {
                log.warn("[RateLimit] Login rate limit exceeded for IP: {} (attempts: {})", ip, current);
                HttpServletResponse httpResp = (HttpServletResponse) response;
                httpResp.setStatus(429);
                httpResp.setContentType("application/json;charset=UTF-8");
                httpResp.getWriter().write("{\"code\":429,\"msg\":\"Too many login attempts, please try again later\",\"data\":null}");
                return;
            }
        }

        chain.doFilter(request, response);
        if (protectedRequest && ((HttpServletResponse) response).getStatus() < 400) {
            attempts.invalidate(ip);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        if (isTrustedProxy(request.getRemoteAddr())) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isEmpty()) return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isTrustedProxy(String remoteAddress) {
        List<String> allowlist = trustedProxies == null ? List.of()
                : Arrays.stream(trustedProxies.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (allowlist.isEmpty() || remoteAddress == null) return false;
        try {
            return SsrfAllowlist.matchesAddress(InetAddress.getByName(remoteAddress), allowlist);
        } catch (Exception e) {
            return false;
        }
    }
}

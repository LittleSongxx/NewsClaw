package vip.newsclaw.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vip.newsclaw.auth.service.AuthService;

import java.io.IOException;

/** Restricts the seeded administrator to changing its published default password. */
@Component
@RequiredArgsConstructor
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private final AuthService authService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && request.getRequestURI().startsWith("/api/")
                && authService.mustChangePassword(auth.getName())
                && !isPasswordChange(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":403,\"msg\":\"Bootstrap password must be changed before using the API\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isPasswordChange(HttpServletRequest request) {
        return "PUT".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().matches("/api/v1/auth/users/[^/]+/password");
    }
}

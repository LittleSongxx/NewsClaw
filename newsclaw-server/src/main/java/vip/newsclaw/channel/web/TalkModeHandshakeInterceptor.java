package vip.newsclaw.channel.web;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import vip.newsclaw.auth.model.UserEntity;
import vip.newsclaw.auth.pat.PersonalAccessTokenEntity;
import vip.newsclaw.auth.pat.PersonalAccessTokenService;
import vip.newsclaw.auth.pat.PersonalAccessTokenScopePolicy;
import vip.newsclaw.auth.service.AuthService;

import java.util.Map;
import java.util.Optional;

/** Authenticates Talk Mode before an unauthenticated WebSocket reaches the handler. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TalkModeHandshakeInterceptor implements HandshakeInterceptor {

    public static final String USERNAME_ATTR = "newsclaw.talkUser";
    public static final String USER_ID_ATTR = "newsclaw.talkUserId";
    public static final String ROLE_ATTR = "newsclaw.talkRole";

    private final AuthService authService;
    private final PersonalAccessTokenService patService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI())
                .build().getQueryParams().getFirst("token");
        UserEntity user = resolveUser(token);
        if (user == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.warn("[TalkMode] Handshake rejected: missing or invalid token");
            return false;
        }
        attributes.put(USERNAME_ATTR, user.getUsername());
        attributes.put(USER_ID_ATTR, user.getId());
        attributes.put(ROLE_ATTR, user.getRole());
        return true;
    }

    private UserEntity resolveUser(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            if (token.startsWith(PersonalAccessTokenService.PAT_PREFIX)) {
                Optional<PersonalAccessTokenEntity> pat = patService.findActiveByPlaintext(token);
                if (pat.isEmpty()) return null;
                if (!PersonalAccessTokenScopePolicy.allows(pat.get().getScopes(), "chat:write")) return null;
                UserEntity user = authService.findById(pat.get().getUserId());
                return enabled(user) ? user : null;
            }
            Claims claims = authService.parseClaims(token);
            if (claims == null || claims.getSubject() == null) return null;
            UserEntity user = authService.findByUsername(claims.getSubject());
            return enabled(user) ? user : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean enabled(UserEntity user) {
        return user != null && Boolean.TRUE.equals(user.getEnabled());
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}

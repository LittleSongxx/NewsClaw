package vip.newsclaw.channel.web;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;
import vip.newsclaw.auth.model.UserEntity;
import vip.newsclaw.auth.pat.PersonalAccessTokenEntity;
import vip.newsclaw.auth.pat.PersonalAccessTokenService;
import vip.newsclaw.auth.service.AuthService;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TalkModeHandshakeInterceptorTest {

    private AuthService authService;
    private PersonalAccessTokenService patService;
    private TalkModeHandshakeInterceptor interceptor;
    private ServerHttpResponse response;
    private WebSocketHandler handler;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        patService = mock(PersonalAccessTokenService.class);
        interceptor = new TalkModeHandshakeInterceptor(authService, patService);
        response = mock(ServerHttpResponse.class);
        handler = mock(WebSocketHandler.class);
    }

    @Test
    void jwtHandshakePinsServerResolvedIdentity() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("alice");
        when(authService.parseClaims("jwt-token")).thenReturn(claims);
        UserEntity user = enabledUser(7L, "alice");
        when(authService.findByUsername("alice")).thenReturn(user);
        Map<String, Object> attrs = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                request("ws://localhost/api/v1/talk/ws?token=jwt-token"), response, handler, attrs);

        assertThat(accepted).isTrue();
        assertThat(attrs).containsEntry(TalkModeHandshakeInterceptor.USERNAME_ATTR, "alice")
                .containsEntry(TalkModeHandshakeInterceptor.USER_ID_ATTR, 7L);
    }

    @Test
    void patHandshakeIsSupported() {
        PersonalAccessTokenEntity pat = new PersonalAccessTokenEntity();
        pat.setUserId(8L);
        when(patService.findActiveByPlaintext("mc_pat")).thenReturn(Optional.of(pat));
        when(authService.findById(8L)).thenReturn(enabledUser(8L, "bob"));

        boolean accepted = interceptor.beforeHandshake(
                request("ws://localhost/api/v1/talk/ws?token=mc_pat"), response, handler, new HashMap<>());

        assertThat(accepted).isTrue();
    }

    @Test
    void missingTokenIsRejectedBeforeHandler() {
        boolean accepted = interceptor.beforeHandshake(
                request("ws://localhost/api/v1/talk/ws"), response, handler, new HashMap<>());

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void crossSiteOriginIsRejectedByTalkHandshakeChain() throws Exception {
        ServerHttpRequest request = request("https://app.example/api/v1/talk/ws?token=jwt-token");
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://evil.example");
        when(request.getHeaders()).thenReturn(headers);

        boolean accepted = new OriginHandshakeInterceptor().beforeHandshake(
                request, response, handler, new HashMap<>());

        assertThat(accepted).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    private static ServerHttpRequest request(String uri) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        return request;
    }

    private static UserEntity enabledUser(Long id, String username) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername(username);
        user.setRole("user");
        user.setEnabled(true);
        return user;
    }
}

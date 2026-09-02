package vip.newsclaw.channel.webchat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebChatOriginInterceptorTest {
    @Test
    void allowListAcceptsExactOriginAndTrailingSlash() {
        String config = "{\"allowed_origins\":[\"https://app.example.com\"]}";
        assertTrue(WebChatOriginInterceptor.originAllowed("https://app.example.com/", config));
        assertFalse(WebChatOriginInterceptor.originAllowed("https://evil.example.com", config));
    }

    @Test
    void commaSeparatedAllowListAndWildcardAreSupported() {
        assertTrue(WebChatOriginInterceptor.originAllowed(
                "https://app.example.com", "{\"allowed_origins\":\"https://a.example.com, https://app.example.com\"}"));
        assertTrue(WebChatOriginInterceptor.originAllowed(
                "https://any.example", "{\"allowed_origins\":[\"*\"]}"));
        assertFalse(WebChatOriginInterceptor.originAllowed(
                "https://any.example", "{\"allowed_origins\":[]}"));
    }
}

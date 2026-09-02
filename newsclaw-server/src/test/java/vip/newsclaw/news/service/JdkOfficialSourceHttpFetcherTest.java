package vip.newsclaw.news.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JdkOfficialSourceHttpFetcherTest {

    @Test
    @DisplayName("只读抓取器在建连前拒绝本地地址，避免 SSRF")
    void blocksLoopbackBeforeNetworkCall() {
        JdkOfficialSourceHttpFetcher fetcher = new JdkOfficialSourceHttpFetcher();

        assertThrows(SecurityException.class,
                () -> fetcher.fetch("http://127.0.0.1:18080/private", 1024, 1, 0));
    }

    @Test
    void marksOversizedBodiesIncompleteInsteadOfSilentlyTreatingTruncationAsEvidence() throws Exception {
        JdkOfficialSourceHttpFetcher.BoundedBody body = JdkOfficialSourceHttpFetcher.readBounded(
                new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)),
                4, StandardCharsets.UTF_8);

        assertEquals("abcd", body.text());
        assertFalse(body.complete());
    }

    @Test
    void acceptsOnlyCredentialFreeHttpProxyEndpoints() {
        var proxy = JdkOfficialSourceHttpFetcher.proxyAddress("http://proxy.example:8080");

        assertEquals("proxy.example", proxy.getHostString());
        assertEquals(8080, proxy.getPort());
        assertTrue(proxy.isUnresolved());
        assertThrows(IllegalArgumentException.class,
                () -> JdkOfficialSourceHttpFetcher.proxyAddress("https://proxy.example:8443"));
        assertThrows(IllegalArgumentException.class,
                () -> JdkOfficialSourceHttpFetcher.proxyAddress("http://user:secret@proxy.example:8080"));
        assertThrows(IllegalArgumentException.class,
                () -> JdkOfficialSourceHttpFetcher.proxyAddress("http://proxy.example:8080/path"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void fallsBackToProxyOnlyAfterDirectTransportFailureAndRecordsRoute() throws Exception {
        HttpClient direct = mock(HttpClient.class);
        HttpClient proxy = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(direct.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("direct timeout"));
        when(proxy.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(
                "<html><body>publisher content</body></html>".getBytes(StandardCharsets.UTF_8)));
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("content-type", List.of("text/html; charset=utf-8")), (left, right) -> true));

        JdkOfficialSourceHttpFetcher fetcher = new JdkOfficialSourceHttpFetcher(direct, proxy);
        OfficialSourceHttpFetcher.FetchResult result = fetcher.fetch(
                "https://93.184.216.34/story", 1024, 1, 0);

        assertEquals(200, result.httpStatus());
        assertEquals("proxy_fallback", result.transportRoute());
        assertTrue(result.bodyComplete());
        verify(direct).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(proxy).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verifyNoMoreInteractions(direct, proxy);
    }
}

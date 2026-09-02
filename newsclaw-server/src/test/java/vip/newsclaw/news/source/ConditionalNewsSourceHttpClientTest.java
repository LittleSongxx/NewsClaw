package vip.newsclaw.news.source;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConditionalNewsSourceHttpClientTest {

    @Test
    void sendsStoredEtagAndLastModifiedValidatorsOnNextPoll() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> first = response(200, Map.of(
                "etag", List.of("\"v1\""),
                "last-modified", List.of("Wed, 26 Aug 2026 10:15:00 GMT")));
        HttpResponse<byte[]> second = response(304, Map.of());
        List<HttpRequest> requests = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(0));
                    return calls.getAndIncrement() == 0 ? first : second;
                });
        ConditionalNewsSourceHttpClient conditional = new ConditionalNewsSourceHttpClient(client);
        URI endpoint = URI.create("https://93.184.216.34/feed.xml");

        conditional.fetch(endpoint);
        ConditionalNewsSourceHttpClient.FetchResponse fetched = conditional.fetch(endpoint);

        assertTrue(fetched.notModified());
        HttpRequest request = requests.get(1);
        assertEquals("\"v1\"", request.headers().firstValue("If-None-Match").orElseThrow());
        assertEquals("Wed, 26 Aug 2026 10:15:00 GMT",
                request.headers().firstValue("If-Modified-Since").orElseThrow());
    }

    private static HttpResponse<byte[]> response(int status, Map<String, List<String>> headers) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new byte[0]);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (left, right) -> true));
        return response;
    }
}

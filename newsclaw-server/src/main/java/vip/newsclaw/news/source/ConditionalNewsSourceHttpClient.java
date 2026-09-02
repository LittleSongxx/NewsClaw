package vip.newsclaw.news.source;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RFC 9110 validator-aware GET helper for operator-configured source endpoints.
 * Parsed payload caching remains adapter-specific because a feed and a sitemap
 * have different normalized representations.
 */
final class ConditionalNewsSourceHttpClient {

    private final HttpClient client;
    private final Map<URI, Validators> validators = new ConcurrentHashMap<>();

    ConditionalNewsSourceHttpClient(HttpClient client) {
        this.client = client;
    }

    FetchResponse fetch(URI uri) throws Exception {
        return fetch(uri, NewsSourceValidators.EMPTY);
    }

    FetchResponse fetch(URI uri, NewsSourceValidators persisted) throws Exception {
        Validators memory = validators.get(uri);
        NewsSourceValidators previous = persisted != null && !persisted.empty()
                ? persisted : memory == null ? NewsSourceValidators.EMPTY
                : new NewsSourceValidators(memory.etag(), memory.lastModified());
        return execute(uri, previous, true);
    }

    FetchResponse fetchUnconditional(URI uri) throws Exception {
        return execute(uri, NewsSourceValidators.EMPTY, false);
    }

    private FetchResponse execute(URI uri, NewsSourceValidators previous,
                                  boolean conditional) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        if (conditional && previous != null) {
            if (!previous.etag().isBlank()) headers.put("If-None-Match", previous.etag());
            if (!previous.lastModified().isBlank()) {
                headers.put("If-Modified-Since", previous.lastModified());
            }
        }
        Instant startedAt = Instant.now();
        HttpResponse<byte[]> response = NewsSourceHttpSupport.get(client, uri, false, headers);
        String etag = response.headers().firstValue("etag")
                .orElse(previous == null ? "" : previous.etag());
        String lastModified = response.headers().firstValue("last-modified")
                .orElse(previous == null ? "" : previous.lastModified());
        if (response.statusCode() == 304 || response.statusCode() / 100 == 2) {
            validators.put(uri, new Validators(etag, lastModified));
        }
        return new FetchResponse(uri, response, response.statusCode() == 304,
                etag, lastModified, startedAt, Instant.now());
    }

    record FetchResponse(URI requestUrl,
                         HttpResponse<byte[]> response,
                         boolean notModified,
                         String etag,
                         String lastModified,
                         Instant startedAt,
                         Instant observedAt) {

        NewsSourceTransportRecord transport() {
            byte[] body = response.body() == null ? new byte[0] : response.body();
            URI finalUrl = response.uri() == null ? requestUrl : response.uri();
            return new NewsSourceTransportRecord(requestUrl, finalUrl,
                    response.statusCode(), header("content-type"), etag, lastModified,
                    header("retry-after"), contentLength(), body, false, notModified,
                    startedAt, observedAt, "", "");
        }

        private String header(String name) {
            return response.headers() == null ? ""
                    : response.headers().firstValue(name).orElse("");
        }

        private Long contentLength() {
            String value = header("content-length");
            if (value.isBlank()) return null;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private record Validators(String etag, String lastModified) {
        private Validators {
            etag = etag == null ? "" : etag;
            lastModified = lastModified == null ? "" : lastModified;
        }
    }
}

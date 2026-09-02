package vip.newsclaw.news.source;

import vip.newsclaw.tool.browser.UrlSafetyChecker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/** Small bounded, read-only HTTP helper shared by source adapters. */
final class NewsSourceHttpSupport {

    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private NewsSourceHttpSupport() {
    }

    static HttpResponse<byte[]> get(HttpClient client, URI uri) throws Exception {
        return get(client, uri, false);
    }

    /**
     * Fetch a deployment-configured endpoint. Private-network access is only
     * appropriate for a fixed, operator-configured sidecar such as the local
     * SearXNG container; model-supplied result and fetch URLs must keep using
     * the strict overload above.
     */
    static HttpResponse<byte[]> get(HttpClient client, URI uri, boolean allowPrivateNetwork) throws Exception {
        return get(client, uri, allowPrivateNetwork, Map.of());
    }

    static HttpResponse<byte[]> get(HttpClient client, URI uri, boolean allowPrivateNetwork,
                                    Map<String, String> requestHeaders) throws Exception {
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("source URL must be an absolute http/https URI");
        }
        // Source providers may receive URLs originating from model output.
        // Reuse the platform SSRF guard so a provider cannot become a
        // backdoor into loopback, metadata or private network services.
        UrlSafetyChecker.check(uri.toString(), allowPrivateNetwork);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "text/html, application/xml, application/rss+xml, application/atom+xml, application/json")
                .header("User-Agent", "NewsClaw-NewsSource/1.0")
                .GET();
        if (requestHeaders != null) {
            requestHeaders.forEach((name, value) -> {
                if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                    request.header(name, value);
                }
            });
        }
        HttpResponse<byte[]> response = client.send(request.build(), responseInfo -> {
            long declaredLength = responseInfo.headers()
                    .firstValueAsLong("content-length").orElse(-1L);
            if (declaredLength > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("source response exceeds " + MAX_BODY_BYTES + " bytes");
            }
            return HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofInputStream(),
                    NewsSourceHttpSupport::readBounded);
        });
        return response;
    }

    /** Read chunked/unknown-length responses without allocating an unbounded body. */
    private static byte[] readBounded(InputStream input) {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > MAX_BODY_BYTES - total) {
                    throw new IllegalArgumentException(
                            "source response exceeds " + MAX_BODY_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(HttpResponse<byte[]> response) {
        if (response == null || response.body() == null) return "";
        return new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    static String stripHtml(String value) {
        if (value == null) return "";
        return value.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}

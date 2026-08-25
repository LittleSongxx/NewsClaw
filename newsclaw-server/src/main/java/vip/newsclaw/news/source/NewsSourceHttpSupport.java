package vip.newsclaw.news.source;

import vip.newsclaw.tool.browser.UrlSafetyChecker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("source URL must be an absolute http/https URI");
        }
        // Source providers may receive URLs originating from model output.
        // Reuse the platform SSRF guard so a provider cannot become a
        // backdoor into loopback, metadata or private network services.
        UrlSafetyChecker.check(uri.toString(), allowPrivateNetwork);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "text/html, application/xml, application/rss+xml, application/atom+xml, application/json")
                .header("User-Agent", "NewsClaw-NewsSource/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.body() != null && response.body().length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("source response exceeds " + MAX_BODY_BYTES + " bytes");
        }
        return response;
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

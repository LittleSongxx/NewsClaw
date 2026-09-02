package vip.newsclaw.news.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vip.newsclaw.tool.browser.UrlSafetyChecker;

import java.io.InputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deliberately small HTTP implementation for official evidence capture.
 *
 * <p>It only sends GET requests, carries no cookies or user credentials, does
 * not evaluate JavaScript, and manually handles redirects so every target gets
 * the same SSRF check. This is intentionally not a generic browser tool.</p>
 */
@Component
public class JdkOfficialSourceHttpFetcher implements OfficialSourceHttpFetcher {

    private final HttpClient directClient;
    private final HttpClient proxyClient;

    @Autowired
    public JdkOfficialSourceHttpFetcher(AiNewsOfficialCaptureProperties properties) {
        this.directClient = clientBuilder().build();
        InetSocketAddress proxy = proxyAddress(properties == null ? null : properties.getProxyUrl());
        this.proxyClient = proxy == null ? null
                : clientBuilder().proxy(ProxySelector.of(proxy)).build();
    }

    private static HttpClient.Builder clientBuilder() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15));
    }

    JdkOfficialSourceHttpFetcher() {
        this(new AiNewsOfficialCaptureProperties());
    }

    /** Package-private seam for deterministic transport-routing tests. */
    JdkOfficialSourceHttpFetcher(HttpClient directClient, HttpClient proxyClient) {
        this.directClient = directClient;
        this.proxyClient = proxyClient;
    }

    @Override
    public FetchResult fetch(String sourceUrl, int maxBytes, int timeoutSeconds, int maxRedirects) throws Exception {
        try {
            return fetch(directClient, "direct", sourceUrl, maxBytes, timeoutSeconds, maxRedirects);
        } catch (InterruptedException error) {
            throw error;
        } catch (Exception directFailure) {
            if (proxyClient == null || !(directFailure instanceof IOException)) throw directFailure;
            return fetch(proxyClient, "proxy_fallback", sourceUrl, maxBytes, timeoutSeconds, maxRedirects);
        }
    }

    private FetchResult fetch(HttpClient httpClient, String transportRoute,
                              String sourceUrl, int maxBytes,
                              int timeoutSeconds, int maxRedirects) throws Exception {
        String current = sourceUrl;
        List<String> redirects = new ArrayList<>();
        int safeMaxBytes = Math.max(1_024, Math.min(maxBytes, 2_000_000));
        Duration timeout = Duration.ofSeconds(Math.max(1, Math.min(timeoutSeconds, 60)));
        for (int hop = 0; hop <= Math.max(0, Math.min(maxRedirects, 10)); hop++) {
            UrlSafetyChecker.check(current);
            HttpRequest request = HttpRequest.newBuilder(URI.create(current))
                    .GET()
                    .timeout(timeout)
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
                    .header("User-Agent", "NewsClaw-AI-News-Evidence/1.0")
                    .build();
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("location").orElse(null);
                try (InputStream ignored = response.body()) {
                    // Redirect responses are never retained as evidence bodies.
                }
                if (location == null || location.isBlank()) {
                    return new FetchResult(current, status, "", contentType(response),
                            LocalDateTime.now(), redirects, true, retryAfter(response), transportRoute);
                }
                if (hop >= Math.max(0, Math.min(maxRedirects, 10))) {
                    throw new IllegalStateException("官方来源重定向次数超过上限");
                }
                redirects.add(current);
                current = URI.create(current).resolve(location.trim()).toString();
                continue;
            }
            BoundedBody body;
            try (InputStream input = response.body()) {
                body = readBounded(input, safeMaxBytes, charset(contentType(response)));
            }
            return new FetchResult(current, status, body.text(), contentType(response),
                    LocalDateTime.now(), redirects, body.complete(), retryAfter(response), transportRoute);
        }
        throw new IllegalStateException("官方来源重定向处理异常");
    }

    static BoundedBody readBounded(InputStream input, int maxBytes, Charset charset) throws Exception {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        int length = Math.min(bytes.length, maxBytes);
        return new BoundedBody(new String(bytes, 0, length, charset), bytes.length <= maxBytes);
    }

    static InetSocketAddress proxyAddress(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI proxy = URI.create(raw.trim());
            String path = proxy.getPath();
            if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                    || proxy.getUserInfo() != null || proxy.getQuery() != null || proxy.getFragment() != null
                    || path != null && !path.isBlank() && !"/".equals(path)) {
                throw new IllegalArgumentException("invalid proxy URL");
            }
            int port = proxy.getPort() < 0 ? 80 : proxy.getPort();
            if (port < 1 || port > 65_535) throw new IllegalArgumentException("invalid proxy port");
            return InetSocketAddress.createUnresolved(proxy.getHost(), port);
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "AI 新闻来源抓取代理必须是无凭证、无路径的 http://host:port", error);
        }
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("content-type").orElse("");
    }

    private static String retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after").orElse(null);
    }

    private static Charset charset(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            int index = lower.indexOf("charset=");
            if (index >= 0) {
                String raw = lower.substring(index + 8).split("[;\\s]", 2)[0].replace("\"", "").trim();
                try {
                    return Charset.forName(raw);
                } catch (Exception ignored) {
                    // UTF-8 is the safe, deterministic fallback for evidence text.
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    record BoundedBody(String text, boolean complete) {
    }
}

package vip.newsclaw.news.service;

import org.springframework.stereotype.Component;
import vip.newsclaw.tool.browser.UrlSafetyChecker;

import java.io.InputStream;
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

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public FetchResult fetch(String sourceUrl, int maxBytes, int timeoutSeconds, int maxRedirects) throws Exception {
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
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("location").orElse(null);
                try (InputStream ignored = response.body()) {
                    // Redirect responses are never retained as evidence bodies.
                }
                if (location == null || location.isBlank()) {
                    return new FetchResult(current, status, "", contentType(response), LocalDateTime.now(), redirects);
                }
                if (hop >= Math.max(0, Math.min(maxRedirects, 10))) {
                    throw new IllegalStateException("官方来源重定向次数超过上限");
                }
                redirects.add(current);
                current = URI.create(current).resolve(location.trim()).toString();
                continue;
            }
            String body;
            try (InputStream input = response.body()) {
                body = readBounded(input, safeMaxBytes, charset(contentType(response)));
            }
            return new FetchResult(current, status, body, contentType(response), LocalDateTime.now(), redirects);
        }
        throw new IllegalStateException("官方来源重定向处理异常");
    }

    private static String readBounded(InputStream input, int maxBytes, Charset charset) throws Exception {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        int length = Math.min(bytes.length, maxBytes);
        return new String(bytes, 0, length, charset);
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("content-type").orElse("");
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
}

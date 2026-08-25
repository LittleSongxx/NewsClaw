package vip.newsclaw.news.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Optional SearXNG JSON adapter; disabled until a base URL is configured. */
@Component
public class SearxngNewsSourceProvider implements NewsSourceProvider {

    private final ObjectMapper objectMapper;
    private final AiNewsSourceRegistry sourceRegistry;
    private final HttpClient client;
    private final String baseUrl;
    private final boolean allowPrivateEndpoint;

    public SearxngNewsSourceProvider(ObjectMapper objectMapper,
                                     AiNewsSourceRegistry sourceRegistry,
                                     @Value("${newsclaw.ai-news.sources.searxng.base-url:${SEARXNG_BASE_URL:}}") String baseUrl,
                                     @Value("${newsclaw.ai-news.sources.searxng.allow-private-endpoint:false}") boolean allowPrivateEndpoint) {
        this.objectMapper = objectMapper;
        this.sourceRegistry = sourceRegistry;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.allowPrivateEndpoint = allowPrivateEndpoint;
        this.client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    @Override
    public String providerId() {
        return "searxng";
    }

    @Override
    public List<NewsSourceResult> search(NewsSourceQuery query) {
        if (baseUrl.isBlank() || query == null || query.query().isBlank()) return List.of();
        try {
            String endpoint = searchEndpoint(baseUrl);
            String separator = endpoint.contains("?") ? "&" : "?";
            URI uri = URI.create(endpoint + separator + "q="
                    + URLEncoder.encode(query.query(), StandardCharsets.UTF_8)
                    + "&format=json&categories=news&language="
                    + URLEncoder.encode(query.language().isBlank() ? "auto" : query.language(), StandardCharsets.UTF_8));
            // The base URL comes from deployment configuration, never from an
            // Agent call. Result URLs still use the strict fetch overload.
            HttpResponse<byte[]> response = NewsSourceHttpSupport.get(client, uri, allowPrivateEndpoint);
            if (response.statusCode() < 200 || response.statusCode() >= 300) return List.of();
            JsonNode root = objectMapper.readTree(response.body());
            List<NewsSourceResult> out = new ArrayList<>();
            JsonNode results = root.path("results");
            if (!results.isArray()) return List.of();
            for (JsonNode item : results) {
                String url = item.path("url").asText("");
                if (url.isBlank()) continue;
                String snippet = item.path("content").asText("");
                out.add(result(url, item.path("title").asText(""), snippet,
                        response.statusCode(), "SEARXNG_SEARCH", Map.of("engine", item.path("engine").asText(""))));
                if (out.size() >= query.limit()) break;
            }
            return List.copyOf(out);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @Override
    public Optional<NewsSourceResult> fetch(URI url) {
        try {
            HttpResponse<byte[]> response = NewsSourceHttpSupport.get(client, url);
            if (response.statusCode() < 200 || response.statusCode() >= 300) return Optional.empty();
            String body = NewsSourceHttpSupport.stripHtml(NewsSourceHttpSupport.text(response));
            return Optional.of(result(url.toString(), "", NewsSourceHttpSupport.truncate(body, 20_000),
                    response.statusCode(), "SEARXNG_FETCH", Map.of()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public NewsSourceHealth health() {
        return baseUrl.isBlank()
                ? NewsSourceHealth.disabled(providerId(), "newsclaw.ai-news.sources.searxng.base-url is empty")
                : NewsSourceHealth.healthy(providerId(), 0L);
    }

    static String searchEndpoint(String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            throw new IllegalArgumentException("SearXNG base URL is empty");
        }
        URI parsed;
        try {
            parsed = URI.create(configuredBaseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SearXNG base URL is invalid", e);
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()
                || !("http".equalsIgnoreCase(parsed.getScheme())
                || "https".equalsIgnoreCase(parsed.getScheme()))) {
            throw new IllegalArgumentException("SearXNG base URL must be an absolute http/https URI");
        }

        String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
        while (path.endsWith("/") && !path.isEmpty()) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.endsWith("/search")) {
            path += "/search";
        }
        if (path.isEmpty()) path = "/search";

        // Preserve deployment-supplied query parameters (for example a tenant
        // or language default), while deliberately dropping fragments because
        // they are never sent in an HTTP request.
        StringBuilder endpoint = new StringBuilder()
                .append(parsed.getScheme()).append("://")
                .append(parsed.getRawAuthority()).append(path);
        if (parsed.getRawQuery() != null && !parsed.getRawQuery().isBlank()) {
            endpoint.append('?').append(parsed.getRawQuery());
        }
        return endpoint.toString();
    }

    private NewsSourceResult result(String url, String title, String body, int status,
                                    String method, Map<String, Object> metadata) {
        String canonical = AiNewsEventService.canonicalUrl(url);
        String tier = sourceRegistry.isOfficialUrl(url) ? "official"
                : sourceRegistry.isTrustedMediaUrl(url) ? "media" : "community";
        return new NewsSourceResult(title, body, body,
                new NewsSourceProvenance(providerId(), tier, url, canonical, Instant.now(),
                        status, method, metadata));
    }
}

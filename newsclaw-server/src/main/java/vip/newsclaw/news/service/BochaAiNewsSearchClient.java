package vip.newsclaw.news.service;

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.newsclaw.news.source.NewsSourceHashing;

import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dedicated, feature-flagged adapter for Bocha Web Search's China lanes. */
@Service
public class BochaAiNewsSearchClient {

    private static final String PROVIDER_ID = "bocha-web";
    private static final int MAX_RESPONSE_CHARS = 2_000_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final AiNewsCandidatePipelineProperties properties;
    private final ObjectMapper objectMapper;
    private final Transport transport;

    @Autowired
    public BochaAiNewsSearchClient(AiNewsCandidatePipelineProperties properties,
                                   ObjectMapper objectMapper) {
        this(properties, objectMapper, new HutoolTransport());
    }

    BochaAiNewsSearchClient(AiNewsCandidatePipelineProperties properties,
                            ObjectMapper objectMapper,
                            Transport transport) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    public CollectionResult collect(Instant windowStart, Instant windowEnd) {
        var config = properties.getChinaSearch();
        if (!config.isEnabled()) {
            return disabled("DISABLED_BY_CONFIG", windowStart, windowEnd);
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return disabled("DISABLED_MISSING_CREDENTIAL", windowStart, windowEnd);
        }
        URI endpoint;
        try {
            endpoint = URI.create(config.getBaseUrl().trim());
            if (!endpoint.isAbsolute() || endpoint.getHost() == null
                    || !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("HTTPS endpoint required");
            }
        } catch (Exception error) {
            return disabled("DISABLED_INVALID_ENDPOINT", windowStart, windowEnd);
        }

        List<String> configuredQueries = config.getQueries() == null
                ? List.of() : config.getQueries();
        List<AiNewsDiscoverySearchService.QuerySnapshot> snapshots = new ArrayList<>();
        List<AiNewsDiscoverySearchService.QueryExecution> executions = new ArrayList<>();
        int lane = 0;
        for (String configured : configuredQueries) {
            if (configured == null || configured.isBlank()) continue;
            lane++;
            String family = "china_web_" + lane;
            String query = bounded(configured, 256);
            List<AiNewsDiscoverySearchService.SnapshotResult> rows = List.of();
            String failure = "";
            try {
                rows = search(endpoint.toString(), config.getApiKey(), query,
                        count(config.getCount()), freshness(windowStart, windowEnd),
                        config.getTimeoutSeconds());
            } catch (Exception error) {
                failure = "PROVIDER_ERROR:" + safe(error.getMessage());
            }
            String hash = hash(rows);
            snapshots.add(new AiNewsDiscoverySearchService.QuerySnapshot(
                    family, PROVIDER_ID, false, hash, query, "news",
                    date(windowStart), date(windowEnd), List.of(), rows));
            executions.add(new AiNewsDiscoverySearchService.QueryExecution(
                    family, PROVIDER_ID, rows.size(), failure, query,
                    date(windowStart), date(windowEnd), List.of(), false, hash));
        }
        if (snapshots.isEmpty()) {
            return disabled("DISABLED_EMPTY_QUERY_PACK", windowStart, windowEnd);
        }
        return new CollectionResult(true, PROVIDER_ID, "ENABLED", snapshots, executions);
    }

    private List<AiNewsDiscoverySearchService.SnapshotResult> search(
            String endpoint,
            String apiKey,
            String query,
            int count,
            String freshness,
            int timeoutSeconds) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", query);
        request.put("freshness", freshness);
        request.put("summary", true);
        request.put("count", count);
        Response response = transport.execute(endpoint, apiKey,
                objectMapper.writeValueAsString(request),
                Math.min(Math.max(1, timeoutSeconds), 60) * 1000);
        if (response.status() < 200 || response.status() >= 300) {
            throw new IllegalStateException("HTTP " + response.status());
        }
        if (response.body() == null || response.body().length() > MAX_RESPONSE_CHARS) {
            throw new IllegalStateException("response missing or too large");
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode code = root.path("code");
        if (!code.isMissingNode() && code.asInt(-1) != 200) {
            throw new IllegalStateException("provider code " + code.asText()
                    + ": " + safe(root.path("message").asText("")));
        }
        JsonNode results = root.path("data").path("webPages").path("value");
        if (!results.isArray()) {
            throw new IllegalStateException("data.webPages.value is not an array");
        }
        List<AiNewsDiscoverySearchService.SnapshotResult> rows = new ArrayList<>();
        int rank = 0;
        for (JsonNode item : results) {
            rank++;
            String summary = item.path("summary").asText("");
            if (summary.isBlank()) summary = item.path("snippet").asText("");
            rows.add(new AiNewsDiscoverySearchService.SnapshotResult(rank,
                    bounded(item.path("name").asText(""), 512),
                    bounded(item.path("url").asText(""), 4096),
                    bounded(summary, 1500),
                    bounded(item.path("datePublished").asText(""), 512),
                    bounded(item.path("siteName").asText(""), 256),
                    PROVIDER_ID, null));
        }
        return List.copyOf(rows);
    }

    private CollectionResult disabled(String reason,
                                      Instant windowStart,
                                      Instant windowEnd) {
        String family = "china_web_disabled";
        String hash = NewsSourceHashing.sha256(reason);
        var snapshot = new AiNewsDiscoverySearchService.QuerySnapshot(
                family, PROVIDER_ID, false, hash, "disabled", "news",
                date(windowStart), date(windowEnd), List.of(), List.of());
        var execution = new AiNewsDiscoverySearchService.QueryExecution(
                family, PROVIDER_ID, 0, reason, "disabled",
                date(windowStart), date(windowEnd), List.of(), false, hash);
        return new CollectionResult(false, PROVIDER_ID, reason,
                List.of(snapshot), List.of(execution));
    }

    private String hash(Object value) {
        try {
            return NewsSourceHashing.sha256(objectMapper.writeValueAsString(value));
        } catch (Exception error) {
            throw new IllegalStateException("failed to hash China search snapshot", error);
        }
    }

    private static int count(int configured) {
        return Math.min(Math.max(1, configured), 50);
    }

    private static String freshness(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) return "noLimit";
        LocalDate first = date(start);
        LocalDate last = date(end.minusNanos(1));
        return first.equals(last) ? first.toString() : first + ".." + last;
    }

    private static LocalDate date(Instant value) {
        return value == null ? null : value.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= max) return normalized;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(normalized.charAt(end - 1))) end--;
        return normalized.substring(0, end);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return bounded(value.replaceAll("[\\r\\n]+", " ").trim(), 300);
    }

    interface Transport {
        Response execute(String endpoint, String apiKey, String requestJson,
                         int timeoutMillis) throws Exception;
    }

    private static final class HutoolTransport implements Transport {
        @Override
        public Response execute(String endpoint, String apiKey, String requestJson,
                                int timeoutMillis) {
            try (HttpResponse response = HttpUtil.createPost(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestJson)
                    .timeout(timeoutMillis)
                    .execute()) {
                return new Response(response.getStatus(), readBounded(response, MAX_RESPONSE_BYTES));
            }
        }

        private static String readBounded(HttpResponse response, int maxBytes) {
            try (InputStream in = response.bodyStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (read > maxBytes - total) {
                        throw new IllegalStateException("response missing or too large");
                    }
                    out.write(buffer, 0, read);
                    total += read;
                }
                return out.toString(StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("failed to read provider response", e);
            }
        }
    }

    record Response(int status, String body) {
    }

    public record CollectionResult(
            boolean enabled,
            String providerId,
            String status,
            List<AiNewsDiscoverySearchService.QuerySnapshot> snapshots,
            List<AiNewsDiscoverySearchService.QueryExecution> executions) {
        public CollectionResult {
            snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
            executions = executions == null ? List.of() : List.copyOf(executions);
        }
    }
}

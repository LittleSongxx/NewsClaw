package vip.newsclaw.tool.search;

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.newsclaw.system.model.SystemSettingsDTO;

import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tavily 搜索提供商 — 需要 API Key
 *
 * @author NewsClaw Team
 */
@Slf4j
@Component
public class TavilySearchProvider implements SearchProvider {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private static final String DEFAULT_BASE_URL = "https://api.tavily.com/search";
    private static final Duration MAX_RETRY_AFTER = Duration.ofDays(1);
    private static final Duration MAX_TRANSIENT_WAIT = Duration.ofSeconds(2);
    private static final int MAX_TRANSIENT_ATTEMPTS_PER_KEY = 2;

    private final TavilyApiKeyPool keyPool;
    private final TavilyHttpTransport transport;
    private final Sleeper sleeper;

    public TavilySearchProvider() {
        this(new TavilyApiKeyPool(), new HutoolTavilyHttpTransport(), Thread::sleep);
    }

    TavilySearchProvider(TavilyApiKeyPool keyPool, TavilyHttpTransport transport) {
        this(keyPool, transport, millis -> { });
    }

    TavilySearchProvider(TavilyApiKeyPool keyPool, TavilyHttpTransport transport, Sleeper sleeper) {
        this.keyPool = keyPool;
        this.transport = transport;
        this.sleeper = sleeper;
    }

    @Override
    public String id() {
        return "tavily";
    }

    @Override
    public String label() {
        return "Tavily";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 400;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return config != null && TavilyApiKeyPool.configuredKeyCount(config.getTavilyApiKey()) > 0;
    }

    @Override
    public List<SearchResult> search(String query, SystemSettingsDTO config) {
        return search(SearchQuery.of(query), config);
    }

    @Override
    public List<SearchResult> search(SearchQuery searchQuery, SystemSettingsDTO config) {
        if (config == null) {
            throw new IllegalArgumentException("Tavily search configuration is required");
        }
        String configuredKeys = config.getTavilyApiKey();
        String baseUrl = config.getTavilyBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }

        JSONObject reqBody = new JSONObject()
                .set("query", searchQuery.query())
                .set("max_results", searchQuery.resolvedCount())
                // Make credit usage deterministic: Tavily basic search costs
                // one credit, while advanced search costs two.
                .set("search_depth", "basic");

        if (searchQuery.topic() != null) {
            reqBody.set("topic", searchQuery.topic());
        }
        if (searchQuery.startDate() != null) {
            reqBody.set("start_date", searchQuery.startDate().toString());
        }
        if (searchQuery.endDate() != null) {
            reqBody.set("end_date", searchQuery.endDate().toString());
        }
        if (searchQuery.hasIncludeDomains()) {
            reqBody.set("include_domains", searchQuery.includeDomains());
        }
        if (searchQuery.hasExcludeDomains()) {
            reqBody.set("exclude_domains", searchQuery.excludeDomains());
        }
        if (searchQuery.hasLanguage()) {
            reqBody.set("language", searchQuery.language().toLowerCase(Locale.ROOT));
        }

        // Tavily's current Search API uses time_range for these canonical
        // freshness values.
        if (searchQuery.hasFreshness()) {
            String timeRange = mapFreshnessToTimeRange(searchQuery.freshness());
            if (timeRange != null) reqBody.set("time_range", timeRange);
        }

        String requestJson = JSONUtil.toJsonStr(reqBody);
        Set<Integer> attempted = new HashSet<>();
        int configuredCount = keyPool.size(configuredKeys);
        if (configuredCount == 0) {
            throw new IllegalStateException("No Tavily API key is configured");
        }
        for (int attempt = 0; attempt < configuredCount; attempt++) {
            TavilyApiKeyPool.Lease lease = keyPool.acquire(configuredKeys, attempted);
            attempted.add(lease.index());
            for (int transientAttempt = 1;
                 transientAttempt <= MAX_TRANSIENT_ATTEMPTS_PER_KEY; transientAttempt++) {
                TavilyHttpResponse response;
                try {
                    response = transport.execute(baseUrl, lease.apiKey(), requestJson);
                } catch (Exception e) {
                    if (transientAttempt < MAX_TRANSIENT_ATTEMPTS_PER_KEY) {
                        waitBeforeTransientRetry(null);
                        continue;
                    }
                    // Network/provider-wide failures do not burn another key.
                    throw new IllegalStateException("Tavily request failed after transient retry", e);
                }
                int status = response.status();
                if (status >= 200 && status < 300) {
                    List<SearchResult> results = parseResponse(response.body());
                    keyPool.markSuccess(lease);
                    log.debug("Tavily search succeeded (keySlot={}/{}, resultCount={})",
                            lease.slot(), lease.total(), results.size());
                    return results;
                }

                TavilyApiKeyPool.FailureKind failureKind = keyFailureKind(status);
                if (failureKind != null) {
                    Duration retryAfter = status == 429 ? parseRetryAfter(response.retryAfter()) : null;
                    keyPool.markFailure(lease, failureKind, retryAfter);
                    log.warn("Tavily key slot {}/{} unavailable (HTTP {}); trying the next configured key",
                            lease.slot(), lease.total(), status);
                    break;
                }
                if (transientStatus(status) && transientAttempt < MAX_TRANSIENT_ATTEMPTS_PER_KEY) {
                    waitBeforeTransientRetry(parseRetryAfter(response.retryAfter()));
                    continue;
                }
                throw new IllegalStateException("Tavily search failed with HTTP " + status);
            }
        }

        throw new IllegalStateException("All configured Tavily API keys were rejected or rate limited");
    }

    private String mapFreshnessToTimeRange(String freshness) {
        String normalized = freshness.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "day", "week", "month", "year" -> normalized;
            default -> null;
        };
    }

    private TavilyApiKeyPool.FailureKind keyFailureKind(int status) {
        return switch (status) {
            case 401 -> TavilyApiKeyPool.FailureKind.AUTHENTICATION;
            case 429 -> TavilyApiKeyPool.FailureKind.RATE_LIMIT;
            case 432, 433 -> TavilyApiKeyPool.FailureKind.QUOTA_LIMIT;
            default -> null;
        };
    }

    private boolean transientStatus(int status) {
        return Set.of(408, 425, 500, 502, 503, 504).contains(status);
    }

    private void waitBeforeTransientRetry(Duration retryAfter) {
        Duration requested = retryAfter == null ? Duration.ofMillis(150) : retryAfter;
        Duration bounded = requested.compareTo(MAX_TRANSIENT_WAIT) > 0
                ? MAX_TRANSIENT_WAIT : requested;
        try {
            sleeper.sleep(Math.max(1L, bounded.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tavily transient retry interrupted", e);
        }
    }

    private Duration parseRetryAfter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            return capRetryAfter(Duration.ofSeconds(Math.max(1, seconds)));
        } catch (NumberFormatException ignored) {
            try {
                Duration until = Duration.between(
                        ZonedDateTime.now(java.time.ZoneOffset.UTC),
                        ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
                return capRetryAfter(until);
            } catch (DateTimeParseException ignoredDate) {
                return null;
            }
        }
    }

    private Duration capRetryAfter(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return Duration.ofSeconds(1);
        }
        return duration.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : duration;
    }

    private List<SearchResult> parseResponse(String response) {
        List<SearchResult> results = new ArrayList<>();
        try {
            JSONObject json = JSONUtil.parseObj(response);
            JSONArray items = json.getJSONArray("results");
            if (items == null) return results;

            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                String url = item.getStr("url");
                results.add(SearchResult.builder()
                        .title(item.getStr("title"))
                        .url(url)
                        .snippet(item.getStr("content"))
                        .source(extractDomain(url))
                        .date(item.getStr("published_date"))
                        .providerId(id())
                        .relevanceScore(item.getDouble("score"))
                        .build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Tavily returned an invalid JSON response", e);
        }
        return results;
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    @FunctionalInterface
    interface TavilyHttpTransport {
        TavilyHttpResponse execute(String baseUrl, String apiKey, String requestJson);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    record TavilyHttpResponse(int status, String body, String retryAfter) {
    }

    private static final class HutoolTavilyHttpTransport implements TavilyHttpTransport {
        @Override
        public TavilyHttpResponse execute(String baseUrl, String apiKey, String requestJson) {
            try (HttpResponse response = HttpUtil.createPost(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestJson)
                    .timeout(15000)
                    .execute()) {
                return new TavilyHttpResponse(
                        response.getStatus(),
                        readBounded(response),
                        response.header("Retry-After"));
            }
        }

        private static String readBounded(HttpResponse response) {
            try (InputStream in = response.bodyStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (read > MAX_RESPONSE_BYTES - total) {
                        throw new IllegalStateException("Tavily response is too large");
                    }
                    out.write(buffer, 0, read);
                    total += read;
                }
                return out.toString(StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("failed to read Tavily response", e);
            }
        }
    }
}

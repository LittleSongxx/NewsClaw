package vip.newsclaw.tool.search;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import vip.newsclaw.system.model.SystemSettingsDTO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TavilySearchProviderTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "NEWSCLAW_TAVILY_LIVE_TEST", matches = "true")
    void livePoolProbeRejectsInvalidFirstSlotThenUsesConfiguredPool() {
        String configuredPool = System.getenv("TAVILY_API_KEYS");
        assertTrue(configuredPool != null && !configuredPool.isBlank(),
                "TAVILY_API_KEYS must be configured for the live probe");

        SystemSettingsDTO config = config("invalid-live-probe-key," + configuredPool);
        config.setTavilyBaseUrl("https://api.tavily.com/search");

        List<SearchResult> results = new TavilySearchProvider().search(
                new SearchQuery("latest artificial intelligence news", "week", "en", 1), config);

        assertFalse(results.isEmpty());
    }

    @Test
    void quotaFailureMovesToNextKeyAndKeepsSuccessfulSlotSticky() {
        RecordingTransport transport = new RecordingTransport(
                response(432, "{}"),
                response(200, successBody()),
                response(200, successBody()));
        TavilySearchProvider provider = provider(transport);
        SystemSettingsDTO config = config("key-a\nkey-b");

        List<SearchResult> first = provider.search(
                new SearchQuery("latest AI news", "day", "en", 3,
                        "news", LocalDate.parse("2026-08-26"), LocalDate.parse("2026-08-28"),
                        List.of("openai.com", "anthropic.com"), List.of("spam.example")), config);
        List<SearchResult> second = provider.search("another query", config);

        assertEquals(List.of("key-a", "key-b", "key-b"), transport.keys);
        assertEquals(1, first.size());
        assertEquals("example.com", first.getFirst().getSource());
        assertEquals("2026-08-27", first.getFirst().getDate());
        assertEquals(0.87D, first.getFirst().getRelevanceScore());
        assertEquals(1, second.size());

        JSONObject request = JSONUtil.parseObj(transport.requestBodies.getFirst());
        assertFalse(request.containsKey("api_key"));
        assertEquals("basic", request.getStr("search_depth"));
        assertEquals("day", request.getStr("time_range"));
        assertEquals(3, request.getInt("max_results"));
        assertEquals("news", request.getStr("topic"));
        assertEquals("2026-08-26", request.getStr("start_date"));
        assertEquals("2026-08-28", request.getStr("end_date"));
        assertEquals(List.of("openai.com", "anthropic.com"), request.getJSONArray("include_domains").toList(String.class));
        assertEquals(List.of("spam.example"), request.getJSONArray("exclude_domains").toList(String.class));
        assertEquals("en", request.getStr("language"));
    }

    @Test
    void retryAfterRateLimitMovesToNextKey() {
        RecordingTransport transport = new RecordingTransport(
                new TavilySearchProvider.TavilyHttpResponse(429, "{}", "120"),
                response(200, successBody()));

        List<SearchResult> results = provider(transport).search("query", config("key-a,key-b"));

        assertEquals(1, results.size());
        assertEquals(List.of("key-a", "key-b"), transport.keys);
    }

    @Test
    void nonKeySpecificServerErrorDoesNotBurnOtherKeys() {
        RecordingTransport transport = new RecordingTransport(response(500, "{}"), response(500, "{}"));
        TavilySearchProvider provider = provider(transport);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> provider.search("query", config("key-a,key-b")));

        assertEquals(List.of("key-a", "key-a"), transport.keys);
        assertTrue(error.getMessage().contains("HTTP 500"));
    }

    @Test
    void transientServerFailureRetriesSameKeyAndDoesNotConsumeNextAccount() {
        RecordingTransport transport = new RecordingTransport(
                response(503, "{}"), response(200, successBody()));

        List<SearchResult> results = provider(transport).search("query", config("key-a,key-b"));

        assertEquals(1, results.size());
        assertEquals(List.of("key-a", "key-a"), transport.keys);
    }

    @Test
    void exhaustedPoolErrorNeverContainsKeyMaterial() {
        RecordingTransport transport = new RecordingTransport(
                response(433, "{}"), response(401, "{}"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> provider(transport).search("query", config("secret-one,secret-two")));

        assertFalse(error.getMessage().contains("secret-one"));
        assertFalse(error.getMessage().contains("secret-two"));
        assertEquals(2, transport.keys.size());
    }

    private TavilySearchProvider provider(RecordingTransport transport) {
        return new TavilySearchProvider(new TavilyApiKeyPool(), transport);
    }

    private SystemSettingsDTO config(String keys) {
        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setTavilyApiKey(keys);
        config.setTavilyBaseUrl("https://tavily.example.test/search");
        return config;
    }

    private static TavilySearchProvider.TavilyHttpResponse response(int status, String body) {
        return new TavilySearchProvider.TavilyHttpResponse(status, body, null);
    }

    private static String successBody() {
        return """
                {"results":[{"title":"AI update","url":"https://example.com/news/1","content":"Summary","published_date":"2026-08-27","score":0.87}]}
                """;
    }

    private static final class RecordingTransport implements TavilySearchProvider.TavilyHttpTransport {
        private final Deque<TavilySearchProvider.TavilyHttpResponse> responses;
        private final List<String> keys = new ArrayList<>();
        private final List<String> requestBodies = new ArrayList<>();

        private RecordingTransport(TavilySearchProvider.TavilyHttpResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public TavilySearchProvider.TavilyHttpResponse execute(
                String baseUrl, String apiKey, String requestJson) {
            keys.add(apiKey);
            requestBodies.add(requestJson);
            return responses.removeFirst();
        }
    }
}

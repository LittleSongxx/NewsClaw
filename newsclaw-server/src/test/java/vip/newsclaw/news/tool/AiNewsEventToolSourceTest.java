package vip.newsclaw.news.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.service.AiNewsSourceCaptureService;
import vip.newsclaw.news.service.OfficialSourceEvidenceCaptureService;
import vip.newsclaw.news.source.NewsSourceHealth;
import vip.newsclaw.news.source.NewsSourceProvenance;
import vip.newsclaw.news.source.NewsSourceProvider;
import vip.newsclaw.news.source.NewsSourceProviderRegistry;
import vip.newsclaw.news.source.NewsSourceQuery;
import vip.newsclaw.news.source.NewsSourceResult;
import vip.newsclaw.workspace.conversation.ConversationService;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Agent-facing source operations must stay provenance-preserving and read-only. */
class AiNewsEventToolSourceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void searchSourcesReturnsProvenanceWithoutWritingAnEvent() throws Exception {
        AtomicReference<NewsSourceQuery> capturedQuery = new AtomicReference<>();
        NewsSourceResult candidate = result("rss", "https://openai.com/news/example");
        NewsSourceProvider provider = provider("rss", query -> {
            capturedQuery.set(query);
            return List.of(candidate);
        }, url -> Optional.of(candidate));
        AiNewsEventService events = mock(AiNewsEventService.class);
        OfficialSourceEvidenceCaptureService capture = mock(OfficialSourceEvidenceCaptureService.class);
        AiNewsEventTool tool = tool(events, capture, new NewsSourceProviderRegistry(List.of(provider)));

        String output = call(tool, "search_sources", null, "OpenAI release", "rss", null,
                "3", "en", "2026-08-24T00:00:00Z");
        JsonNode json = objectMapper.readTree(output);

        assertEquals("read_only_candidate_sources", json.path("mode").asText());
        assertTrue(json.path("message").asText().contains("capture_source"));
        assertTrue(json.path("message").asText().contains("captureId"));
        assertEquals("rss", json.at("/results/0/provenance/providerId").asText());
        assertEquals("official", json.at("/results/0/provenance/sourceTier").asText());
        assertEquals("https://openai.com/news/example", json.at("/results/0/provenance/canonicalUrl").asText());
        assertEquals("OpenAI release", capturedQuery.get().query());
        assertEquals(3, capturedQuery.get().limit());
        assertEquals("en", capturedQuery.get().language());
        assertEquals(Instant.parse("2026-08-24T00:00:00Z"), capturedQuery.get().since());
        verifyNoInteractions(events, capture);
    }

    @Test
    void fetchSourceRejectsRelativeUrlBeforeCallingAProvider() {
        AtomicInteger fetchCalls = new AtomicInteger();
        NewsSourceProvider provider = provider("rss", query -> List.of(), url -> {
            fetchCalls.incrementAndGet();
            return Optional.empty();
        });
        AiNewsEventTool tool = tool(mock(AiNewsEventService.class),
                mock(OfficialSourceEvidenceCaptureService.class),
                new NewsSourceProviderRegistry(List.of(provider)));

        String output = call(tool, "fetch_source", "/private/path", null, null, "rss",
                null, null, null);

        assertTrue(output.startsWith("Error: sourceUrl must be an absolute http/https URI"), output);
        assertEquals(0, fetchCalls.get());
    }

    @Test
    void fetchSourceRejectsNonHttpSchemeBeforeCallingAProvider() {
        AtomicInteger fetchCalls = new AtomicInteger();
        NewsSourceProvider provider = provider("rss", query -> List.of(), url -> {
            fetchCalls.incrementAndGet();
            return Optional.empty();
        });
        AiNewsEventTool tool = tool(mock(AiNewsEventService.class),
                mock(OfficialSourceEvidenceCaptureService.class),
                new NewsSourceProviderRegistry(List.of(provider)));

        String output = call(tool, "fetch_source", "file:///etc/passwd", null, null, "rss",
                null, null, null);

        assertTrue(output.startsWith("Error: sourceUrl must be an absolute http/https URI"), output);
        assertEquals(0, fetchCalls.get());
    }

    @Test
    void searchSourcesRejectsOutOfRangeLimitBeforeCallingAProvider() {
        AtomicInteger searchCalls = new AtomicInteger();
        NewsSourceProvider provider = provider("rss", query -> {
            searchCalls.incrementAndGet();
            return List.of();
        }, url -> Optional.empty());
        AiNewsEventTool tool = tool(mock(AiNewsEventService.class),
                mock(OfficialSourceEvidenceCaptureService.class),
                new NewsSourceProviderRegistry(List.of(provider)));

        String output = call(tool, "search_sources", null, "AI", "rss", null,
                "101", null, null);

        assertTrue(output.startsWith("Error: sourceLimit must be an integer between 1 and 100"), output);
        assertEquals(0, searchCalls.get());
    }

    @Test
    void validCallSucceedsAfterInvalidParametersWithoutLeakingState() throws Exception {
        AtomicInteger searchCalls = new AtomicInteger();
        NewsSourceProvider provider = provider("rss", query -> {
            searchCalls.incrementAndGet();
            return List.of(result("rss", "https://openai.com/news/recovered"));
        }, url -> Optional.empty());
        AiNewsEventTool tool = tool(mock(AiNewsEventService.class),
                mock(OfficialSourceEvidenceCaptureService.class),
                new NewsSourceProviderRegistry(List.of(provider)));

        String rejected = call(tool, "search_sources", null, "AI", "rss", null,
                "not-a-number", "en", null);
        String recovered = call(tool, "search_sources", null, "AI", "rss", null,
                "2", "en", null);

        assertTrue(rejected.startsWith("Error: sourceLimit must be an integer between 1 and 100"), rejected);
        assertEquals("read_only_candidate_sources",
                objectMapper.readTree(recovered).path("mode").asText());
        assertEquals(1, searchCalls.get(), "the rejected call must not reach or poison the provider");
    }

    private AiNewsEventTool tool(AiNewsEventService events,
                                 OfficialSourceEvidenceCaptureService capture,
                                 NewsSourceProviderRegistry registry) {
        AiNewsEventTool tool = new AiNewsEventTool(events, capture,
                mock(AiNewsSourceCaptureService.class),
                mock(ConversationService.class), objectMapper);
        tool.setSourceProviderRegistry(registry);
        return tool;
    }

    private static String call(AiNewsEventTool tool, String action, String sourceUrl, String query,
                               String providerIds, String providerId, String sourceLimit,
                               String language, String since) {
        return tool.ai_news_event(
                action, null, null, null, null, sourceUrl,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                query, providerIds, providerId, sourceLimit, language, since,
                null, null, null, null,
                null);
    }

    private static NewsSourceProvider provider(String id, Search search, Fetch fetch) {
        return new NewsSourceProvider() {
            @Override public String providerId() { return id; }
            @Override public List<NewsSourceResult> search(NewsSourceQuery query) { return search.run(query); }
            @Override public Optional<NewsSourceResult> fetch(URI url) { return fetch.run(url); }
            @Override public NewsSourceHealth health() { return NewsSourceHealth.healthy(id, 1L); }
        };
    }

    private static NewsSourceResult result(String providerId, String url) {
        return new NewsSourceResult("OpenAI update", "snippet", "content",
                new NewsSourceProvenance(providerId, "official", url, url, Instant.now(),
                        200, "TEST", Map.of("fixture", true)));
    }

    @FunctionalInterface
    private interface Search {
        List<NewsSourceResult> run(NewsSourceQuery query);
    }

    @FunctionalInterface
    private interface Fetch {
        Optional<NewsSourceResult> run(URI url);
    }
}

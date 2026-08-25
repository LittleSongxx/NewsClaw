package vip.newsclaw.news.source;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NewsSourceProviderRegistryTest {

    @Test
    void providerFailureIsolatedAndSuccessfulResultsArePreserved() {
        NewsSourceProvider broken = provider("broken", query -> {
            throw new IllegalStateException("upstream down");
        });
        NewsSourceProvider healthy = provider("healthy", query -> List.of(result("healthy")));

        NewsSourceProviderRegistry registry = new NewsSourceProviderRegistry(List.of(broken, healthy));

        List<NewsSourceResult> results = registry.search(new NewsSourceQuery("AI", 10), List.of());

        assertEquals(1, results.size());
        assertEquals("healthy", results.get(0).title());
        assertEquals("healthy", results.get(0).provenance().providerId());
    }

    @Test
    void duplicateProviderIdsAreRejectedAtConstruction() {
        NewsSourceProvider first = provider("rss", query -> List.of());
        NewsSourceProvider second = provider("rss", query -> List.of());

        assertThrows(IllegalStateException.class,
                () -> new NewsSourceProviderRegistry(List.of(first, second)));
    }

    @Test
    void searchDeduplicatesCanonicalUrlsAndCapsAggregateResults() {
        NewsSourceProvider official = provider("official", query -> List.of(
                result("official", "Official", "https://example.com/story?utm_source=official"),
                result("official", "Official 2", "https://example.com/story-2")));
        NewsSourceProvider media = provider("media", query -> List.of(
                result("media", "Duplicate", "https://example.com/story?utm_source=media"),
                result("media", "Media 2", "https://example.com/story-3")));

        NewsSourceProviderRegistry registry = new NewsSourceProviderRegistry(List.of(official, media));

        List<NewsSourceResult> results = registry.search(new NewsSourceQuery("AI", 2), List.of());

        assertEquals(2, results.size(), "limit applies after cross-provider deduplication");
        assertEquals("official", results.get(0).provenance().providerId(),
                "the first provider wins a canonical URL collision");
        assertEquals("https://example.com/story-2", results.get(1).canonicalUrl());
    }

    private static NewsSourceProvider provider(String id, Search search) {
        return new NewsSourceProvider() {
            @Override public String providerId() { return id; }
            @Override public List<NewsSourceResult> search(NewsSourceQuery query) { return search.run(query); }
            @Override public Optional<NewsSourceResult> fetch(URI url) { return Optional.empty(); }
            @Override public NewsSourceHealth health() { return NewsSourceHealth.healthy(id, 1); }
        };
    }

    private static NewsSourceResult result(String provider) {
        return result(provider, "healthy", "https://example.com/story");
    }

    private static NewsSourceResult result(String provider, String title, String url) {
        // The fixture deliberately maps tracking-query variants to the same
        // canonical URL, as real adapters do before returning a result.
        String canonical = url.substring(0, url.indexOf('?') >= 0 ? url.indexOf('?') : url.length());
        return new NewsSourceResult(title, "snippet", "content",
                new NewsSourceProvenance(provider, "media", url,
                        canonical, Instant.now(), 200, "TEST", Map.of()));
    }

    @FunctionalInterface
    private interface Search {
        List<NewsSourceResult> run(NewsSourceQuery query);
    }
}

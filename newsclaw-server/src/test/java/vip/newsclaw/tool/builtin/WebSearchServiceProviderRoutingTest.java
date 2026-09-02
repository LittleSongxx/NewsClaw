package vip.newsclaw.tool.builtin;

import org.junit.jupiter.api.Test;
import vip.newsclaw.system.model.SystemSettingsDTO;
import vip.newsclaw.system.service.SystemSettingService;
import vip.newsclaw.tool.search.SearchCache;
import vip.newsclaw.tool.search.SearchProvider;
import vip.newsclaw.tool.search.SearchProviderRegistry;
import vip.newsclaw.tool.search.SearchQuery;
import vip.newsclaw.tool.search.SearchResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSearchServiceProviderRoutingTest {

    @Test
    void explicitProviderDoesNotSilentlyFallbackAndExposesFailure() {
        SearchProvider primary = provider("primary", true);
        SearchProvider fallback = provider("fallback", true);
        when(primary.search(org.mockito.ArgumentMatchers.any(SearchQuery.class),
                org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class)))
                .thenThrow(new IllegalStateException("upstream timeout"));
        when(fallback.search(org.mockito.ArgumentMatchers.any(SearchQuery.class),
                org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class)))
                .thenReturn(List.of(result("https://example.com/story")));

        WebSearchService service = service(config(true, "primary", true), primary, fallback);
        WebSearchService.SearchBatch batch = service.searchCandidates(
                "primary", new SearchQuery("latest AI news", null, "en", 5));

        assertTrue(batch.results().isEmpty());
        assertEquals("primary", batch.failures().getFirst().providerId());
        assertTrue(batch.failures().getFirst().message().contains("timeout"));
        verify(fallback, never()).search(org.mockito.ArgumentMatchers.any(SearchQuery.class),
                org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class));
    }

    @Test
    void commonDomainFilterUsesExactOrSubdomainBoundaryAndRetainsCounts() {
        SearchProvider provider = provider("primary", true);
        when(provider.search(org.mockito.ArgumentMatchers.any(SearchQuery.class),
                org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class)))
                .thenReturn(List.of(
                        result("https://example.com/one"),
                        result("https://news.example.com/two"),
                        result("https://badexample.com/three"),
                        result("https://spam.example.net/four")));

        WebSearchService service = service(config(true, "primary", false), provider);
        SearchQuery query = new SearchQuery("AI", null, "en", 20, "news", null, null,
                List.of("www.example.com"), List.of("news.example.com"));
        WebSearchService.SearchBatch batch = service.searchCandidates("primary", query);

        assertEquals(4, batch.suppliedResultCount());
        assertEquals(3, batch.filteredResultCount());
        assertEquals(1, batch.results().size());
        assertEquals("https://example.com/one", batch.results().getFirst().getUrl());
    }

    @Test
    void automaticFallbackKeepsEarlierProviderFailureDiagnostics() {
        SearchProvider primary = provider("primary", true);
        SearchProvider fallback = provider("fallback", true);
        when(primary.search(org.mockito.ArgumentMatchers.any(SearchQuery.class),
                org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class)))
                .thenThrow(new IllegalStateException("HTTP 503"));
        when(fallback.search(org.mockito.ArgumentMatchers.any(SearchQuery.class),
                org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class)))
                .thenReturn(List.of(result("https://example.com/story")));

        WebSearchService service = service(config(true, "primary", true), primary, fallback);
        WebSearchService.SearchBatch batch = service.searchCandidates(
                new SearchQuery("AI", null, "en", 5));

        assertEquals("fallback", batch.providerId());
        assertEquals(1, batch.results().size());
        assertEquals("primary", batch.failures().getFirst().providerId());
        assertTrue(batch.failures().getFirst().message().contains("503"));
    }

    @Test
    void automaticFallbackContinuesWhenPrimaryRowsAllViolateDomainPolicy() {
        SearchProvider primary = provider("primary", true);
        SearchProvider fallback = provider("fallback", true);
        when(primary.search(org.mockito.ArgumentMatchers.any(SearchQuery.class),
                org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class)))
                .thenReturn(List.of(result("https://wrong.example/story")));
        when(fallback.search(org.mockito.ArgumentMatchers.any(SearchQuery.class),
                org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class)))
                .thenReturn(List.of(result("https://allowed.example/story")));
        WebSearchService service = service(config(true, "primary", true), primary, fallback);
        SearchQuery query = new SearchQuery("AI", null, "en", 5, "news", null, null,
                List.of("allowed.example"), List.of());

        WebSearchService.SearchBatch batch = service.searchCandidates(query);

        assertEquals("fallback", batch.providerId());
        assertEquals(1, batch.results().size());
        assertTrue(batch.failures().stream().anyMatch(failure ->
                "primary".equals(failure.providerId())
                        && failure.message().contains("domain policy")));
    }

    @Test
    void explicitUnionKeepsEachProviderObservationAndFailure() {
        SearchProvider primary = provider("primary", true);
        SearchProvider secondary = provider("secondary", true);
        when(primary.search(org.mockito.ArgumentMatchers.any(SearchQuery.class),
                org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class)))
                .thenReturn(List.of(result("https://primary.example/story")));
        when(secondary.search(org.mockito.ArgumentMatchers.any(SearchQuery.class),
                org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class)))
                .thenThrow(new IllegalStateException("secondary timeout"));

        WebSearchService service = service(config(true, "primary", false), primary, secondary);
        WebSearchService.SearchUnion union = service.searchCandidates(
                List.of("primary", "secondary"), new SearchQuery("AI", null, "en", 5));

        assertEquals(2, union.attemptedProviderCount());
        assertEquals(1, union.successfulProviderCount());
        assertEquals(1, union.results().size());
        assertEquals("primary", union.results().getFirst().getProviderId(),
                "the explicit call boundary supplies authoritative provenance");
        assertEquals(1, union.failures().stream()
                .filter(item -> "secondary".equals(item.providerId())).count());
        assertEquals(1, union.batches().stream()
                .filter(item -> "secondary".equals(item.providerId()))
                .findFirst().orElseThrow().failures().size());
    }

    private static WebSearchService service(SystemSettingsDTO config, SearchProvider... providers) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.getSearchSettings()).thenReturn(config);
        return new WebSearchService(settings, new SearchProviderRegistry(List.of(providers)),
                new SearchCache());
    }

    private static SystemSettingsDTO config(boolean enabled, String primary, boolean fallback) {
        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSearchEnabled(enabled);
        config.setSearchProvider(primary);
        config.setSearchFallbackEnabled(fallback);
        return config;
    }

    private static SearchProvider provider(String id, boolean available) {
        SearchProvider provider = mock(SearchProvider.class);
        when(provider.id()).thenReturn(id);
        when(provider.label()).thenReturn(id);
        when(provider.requiresCredential()).thenReturn(false);
        when(provider.autoDetectOrder()).thenReturn(100);
        when(provider.isAvailable(org.mockito.ArgumentMatchers.any(SystemSettingsDTO.class)))
                .thenReturn(available);
        return provider;
    }

    private static SearchResult result(String url) {
        return SearchResult.builder().title("AI story").url(url).snippet("summary").build();
    }
}

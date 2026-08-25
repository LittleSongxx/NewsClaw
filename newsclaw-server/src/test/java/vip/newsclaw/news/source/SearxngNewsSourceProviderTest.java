package vip.newsclaw.news.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearxngNewsSourceProviderTest {

    @Test
    void endpointNormalizationKeepsConfiguredQueryParameters() {
        assertEquals("https://search.example/instance/search?tenant=ai",
                SearxngNewsSourceProvider.searchEndpoint(
                        "https://search.example/instance/search/?tenant=ai#ignored"));
    }

    @Test
    void endpointNormalizationAddsSearchPathToBareBaseUrl() {
        assertEquals("http://localhost:8080/search",
                SearxngNewsSourceProvider.searchEndpoint("http://localhost:8080/"));
    }
}

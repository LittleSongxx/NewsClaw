package vip.newsclaw.news.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void configuredOpenWebSearchPreservesCanonicalProvenance() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = """
                    {"results":[{"url":"https://openai.com/index/open-web-fixture#section",
                    "title":"Official fixture","content":"A bounded search result.","engine":"fixture",
                    "publishedDate":"2026-08-28T08:00:00Z"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            SearxngNewsSourceProvider provider = new SearxngNewsSourceProvider(
                    new ObjectMapper(), new AiNewsSourceRegistry(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), true);

            List<NewsSourceResult> results = provider.search(
                    new NewsSourceQuery("OpenAI release", 3, "en", null));

            assertEquals(1, results.size());
            NewsSourceResult result = results.get(0);
            assertEquals("official", result.provenance().sourceTier());
            assertEquals("searxng", result.provenance().providerId());
            assertEquals("SEARXNG_SEARCH", result.provenance().retrievalMethod());
            assertEquals("https://openai.com/index/open-web-fixture", result.canonicalUrl());
            assertEquals("fixture", result.provenance().metadata().get("engine"));
            assertEquals("2026-08-28T08:00:00Z", result.provenance().metadata().get("publishedAt"));
            assertTrue(rawQuery.get().contains("q=OpenAI+release"));
            assertTrue(rawQuery.get().contains("format=json"));
            assertTrue(rawQuery.get().contains("categories=news"));
            assertTrue(rawQuery.get().contains("language=en"));
        } finally {
            server.stop(0);
        }
    }
}

package vip.newsclaw.news.source;

import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class RssNewsSourceProviderTest {

    @Test
    void parsesRssPublicationTimeAndPreservesFeedProvenance() throws Exception {
        RssNewsSourceProvider provider = new RssNewsSourceProvider(
                new AiNewsSourceRegistry(), "", mock(HttpClient.class));
        HttpResponse<byte[]> response = response("""
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><item>
                  <title>AI startup ships a new agent</title>
                  <link>https://techcrunch.com/2026/08/26/agent-launch/?utm_source=rss</link>
                  <description><![CDATA[The company released an AI research agent.]]></description>
                  <pubDate>Wed, 26 Aug 2026 10:15:00 GMT</pubDate>
                </item></channel></rss>
                """);

        var results = provider.parseFeed(URI.create(
                "https://techcrunch.com/category/artificial-intelligence/feed/"), response,
                new NewsSourceQuery("AI agent", 10));

        assertEquals(1, results.size());
        NewsSourceResult result = results.getFirst();
        assertEquals("media", result.provenance().sourceTier());
        assertEquals("RSS_SEARCH", result.provenance().retrievalMethod());
        assertEquals("2026-08-26T10:15:00Z",
                result.provenance().metadata().get("publishedAt"));
        assertEquals("https://techcrunch.com/2026/08/26/agent-launch",
                result.canonicalUrl());
        assertTrue(result.snippet().contains("research agent"));
    }

    @Test
    void parsesAtomHrefAndIsoTimestamp() throws Exception {
        RssNewsSourceProvider provider = new RssNewsSourceProvider(
                new AiNewsSourceRegistry(), "", mock(HttpClient.class));
        HttpResponse<byte[]> response = response("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom"><entry>
                  <title>New model release</title>
                  <link rel="alternate" href="https://openai.com/index/new-model"/>
                  <summary>A new AI model is available.</summary>
                  <published>2026-08-26T11:15:00+00:00</published>
                </entry></feed>
                """);

        NewsSourceResult result = provider.parseFeed(URI.create(
                "https://openai.com/news/rss.xml"), response,
                new NewsSourceQuery("AI model", 10)).getFirst();

        assertEquals("https://openai.com/index/new-model", result.canonicalUrl());
        assertEquals("official", result.provenance().sourceTier());
        assertEquals("2026-08-26T11:15:00Z",
                result.provenance().metadata().get("publishedAt"));
    }

    @Test
    void skipsItemsWithoutACapturableLinkAndResolvesRelativeLinks() throws Exception {
        RssNewsSourceProvider provider = new RssNewsSourceProvider(
                new AiNewsSourceRegistry(), "", mock(HttpClient.class));
        HttpResponse<byte[]> response = response("""
                <rss version="2.0"><channel>
                  <item><title>Missing URL</title><description>AI model</description></item>
                  <item><title>Relative URL</title><link>/news/relative</link>
                    <description>AI model launch</description><pubDate>2026-08-26</pubDate></item>
                </channel></rss>
                """);

        var results = provider.parseFeed(URI.create("https://publisher.example/feed.xml"),
                response, new NewsSourceQuery("AI", 10));

        assertEquals(1, results.size());
        assertEquals("https://publisher.example/news/relative", results.getFirst().canonicalUrl());
        assertFalse(results.getFirst().provenance().metadata().containsKey("publishedAt"),
                "a date without an explicit timezone is not an exact instant");
        assertEquals("DAY",
                results.getFirst().provenance().metadata().get("publishedAtPrecision"));
    }

    @Test
    void queryMatcherUsesTokenBoundariesAndTimestampParserIsTimezonePreserving() {
        NewsSourceResult unrelated = result("Company said revenue increased", "No model coverage");
        NewsSourceResult relevant = result("Company launches AI assistant", "New model");

        assertFalse(RssNewsSourceProvider.matchesQuery(unrelated,
                new NewsSourceQuery("AI", 10)), "AI must not match the letters in 'said'");
        assertTrue(RssNewsSourceProvider.matchesQuery(relevant,
                new NewsSourceQuery("AI", 10)));
        assertEquals(Instant.parse("2026-08-26T10:15:00Z"),
                RssNewsSourceProvider.parsePublicationInstant(
                        "Wed, 26 Aug 2026 10:15:00 GMT"));
        assertEquals(Instant.parse("2026-08-26T10:15:00Z"),
                RssNewsSourceProvider.parsePublicationInstant(
                        "2026-08-26T18:15:00+08:00"));
        assertEquals(Instant.parse("2026-08-26T10:15:00Z"),
                RssNewsSourceProvider.parsePublicationInstant(
                        "2026-08-26T10:15:00+0000"));
        assertEquals(null, RssNewsSourceProvider.parsePublicationInstant("2026-08-26"),
                "date-only values must not be silently assigned UTC");
    }

    @Test
    void reusesParsedFeedAfterConditionalGetReturnsNotModified() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> first = response("""
                <rss version="2.0"><channel><item>
                  <guid>https://openai.com/index/cached-release</guid>
                  <title>AI cached release</title>
                  <pubDate>Wed, 26 Aug 2026 10:15:00 GMT</pubDate>
                </item></channel></rss>
                """, 200, Map.of("etag", List.of("\"feed-v1\"")));
        HttpResponse<byte[]> second = response("", 304, Map.of());
        List<HttpRequest> requests = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(0));
                    return calls.getAndIncrement() == 0 ? first : second;
                });
        RssNewsSourceProvider provider = new RssNewsSourceProvider(
                new AiNewsSourceRegistry(), "https://93.184.216.34/feed.xml", client);

        assertEquals(1, provider.search(new NewsSourceQuery("AI", 10)).size());
        NewsSourceResult revalidated = provider.search(new NewsSourceQuery("AI", 10)).getFirst();

        assertEquals("RSS_ATOM_REVALIDATED", revalidated.provenance().retrievalMethod());
        assertEquals(304, revalidated.provenance().httpStatus());
        assertEquals(true, revalidated.provenance().metadata().get("revalidated"));
        assertEquals("\"feed-v1\"",
                requests.get(1).headers().firstValue("If-None-Match").orElseThrow());
    }

    @Test
    void healthAggregatesLatestOutcomeAcrossConfiguredEndpoints() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicInteger badEndpointCalls = new AtomicInteger();
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    if (request.uri().getPath().endsWith("bad.xml")
                            && badEndpointCalls.getAndIncrement() == 0) {
                        return response("unavailable", 503, Map.of());
                    }
                    return response("""
                            <rss version="2.0"><channel><item>
                              <guid>https://openai.com/index/health-check</guid>
                              <title>AI health check</title>
                              <pubDate>Wed, 26 Aug 2026 10:15:00 GMT</pubDate>
                            </item></channel></rss>
                            """);
                });
        RssNewsSourceProvider provider = new RssNewsSourceProvider(
                new AiNewsSourceRegistry(),
                "https://93.184.216.34/good.xml,https://93.184.216.34/bad.xml", client);

        List<NewsSourceEndpointDescriptor> endpoints = provider.configuredEndpoints();
        provider.poll(endpoints.get(0), NewsSourceValidators.EMPTY);
        provider.poll(endpoints.get(1), NewsSourceValidators.EMPTY);

        NewsSourceHealth degraded = provider.health();
        assertTrue(degraded.available());
        assertEquals("degraded", degraded.status());
        assertTrue(degraded.message().contains("1 feed(s) last succeeded"), degraded.message());
        assertTrue(degraded.message().contains("1 last failed"), degraded.message());

        provider.poll(endpoints.get(1), NewsSourceValidators.EMPTY);

        NewsSourceHealth recovered = provider.health();
        assertEquals("healthy", recovered.status());
        assertTrue(recovered.message().contains("2 feed(s) last succeeded"), recovered.message());
        assertTrue(recovered.message().contains("0 last failed"), recovered.message());
    }

    private static HttpResponse<byte[]> response(String xml) {
        return response(xml, 200, Map.of());
    }

    private static HttpResponse<byte[]> response(String xml, int status,
                                                  Map<String, List<String>> headers) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(xml.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (left, right) -> true));
        return response;
    }

    private static NewsSourceResult result(String title, String snippet) {
        return new NewsSourceResult(title, snippet, snippet,
                new NewsSourceProvenance("rss", "media", "https://example.com/story",
                        "https://example.com/story", Instant.now(), 200,
                        "TEST", java.util.Map.of()));
    }
}

package vip.newsclaw.news.source;

import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsSitemapSourceProviderTest {

    @Test
    void parsesGoogleNewsMetadataAndKeepsExactTimezone() throws Exception {
        NewsSitemapSourceProvider provider = provider();
        HttpResponse<byte[]> response = response("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"
                        xmlns:news="http://www.google.com/schemas/sitemap-news/0.9">
                  <url>
                    <loc>https://openai.com/index/model-launch/?utm_source=sitemap</loc>
                    <lastmod>2026-08-27T10:00:00Z</lastmod>
                    <news:news>
                      <news:publication><news:name>OpenAI</news:name><news:language>en</news:language></news:publication>
                      <news:publication_date>2026-08-27T09:30:00+00:00</news:publication_date>
                      <news:title>New AI model launch</news:title>
                    </news:news>
                  </url>
                </urlset>
                """);

        NewsSitemapSourceProvider.ParsedSitemap parsed = provider.parseSitemap(
                URI.create("https://openai.com/news-sitemap.xml"), response);

        assertEquals(1, parsed.results().size());
        NewsSourceResult result = parsed.results().getFirst();
        assertEquals("https://openai.com/index/model-launch", result.canonicalUrl());
        assertEquals("official", result.provenance().sourceTier());
        assertEquals("NEWS_SITEMAP_DISCOVERY", result.provenance().retrievalMethod());
        assertEquals("2026-08-27T09:30:00Z",
                result.provenance().metadata().get("publishedAt"));
        assertEquals("OpenAI", result.provenance().metadata().get("publicationName"));
        assertEquals("en", result.provenance().metadata().get("language"));
    }

    @Test
    void recordsDateOnlyPrecisionWithoutInventingAnInstantAndIgnoresGenericUrls() throws Exception {
        NewsSitemapSourceProvider.ParsedSitemap parsed = provider().parseSitemap(
                URI.create("https://example.com/news-sitemap.xml"), response("""
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"
                        xmlns:news="http://www.google.com/schemas/sitemap-news/0.9">
                  <url><loc>https://example.com/generic</loc><lastmod>2026-08-27</lastmod></url>
                  <url><loc>https://example.com/news</loc><news:news>
                    <news:publication_date>2026-08-27</news:publication_date>
                    <news:title>AI release</news:title>
                  </news:news></url>
                </urlset>
                """));

        assertEquals(1, parsed.results().size());
        var metadata = parsed.results().getFirst().provenance().metadata();
        assertEquals("DAY", metadata.get("publishedAtPrecision"));
        assertFalse(metadata.containsKey("publishedAt"));
    }

    @Test
    void parsesSitemapIndexAndRejectsDoctype() throws Exception {
        NewsSitemapSourceProvider provider = provider();
        NewsSitemapSourceProvider.ParsedSitemap parsed = provider.parseSitemap(
                URI.create("https://example.com/index.xml"), response("""
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap><loc>/news-2026-08-27.xml</loc><lastmod>2026-08-27</lastmod></sitemap>
                </sitemapindex>
                """));

        assertEquals(URI.create("https://example.com/news-2026-08-27.xml"),
                parsed.children().getFirst().location());
        assertThrows(Exception.class, () -> provider.parseSitemap(
                URI.create("https://example.com/index.xml"), response("""
                <!DOCTYPE x [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <urlset><url><loc>&xxe;</loc></url></urlset>
                """)));
    }

    private static NewsSitemapSourceProvider provider() {
        return new NewsSitemapSourceProvider(new AiNewsSourceRegistry(), "", mock(HttpClient.class));
    }

    private static HttpResponse<byte[]> response(String xml) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(xml.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(java.util.Map.of(), (left, right) -> true));
        return response;
    }
}

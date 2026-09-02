package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsSourceDocumentParserTest {

    private final AiNewsSourceDocumentParser parser =
            new AiNewsSourceDocumentParser(new ObjectMapper());

    @Test
    void extractsTimezoneBearingJsonLdPublicationTimeAndReadableText() {
        String html = """
                <html><head>
                <meta property="og:title" content="Model X &amp; Safety">
                <script type="application/ld+json">
                  {"@type":"NewsArticle","datePublished":"2026-08-26T12:30:00+08:00"}
                </script>
                <script>ignore this secret()</script>
                </head><body><h1>Model X</h1><p>OpenAI released Model X to developers worldwide.</p></body></html>
                """;

        AiNewsSourceDocumentParser.ParsedDocument result = parser.parse(
                html, "text/html; charset=utf-8", "https://openai.com/index/model-x");

        assertEquals("Model X & Safety", result.title());
        assertEquals(LocalDateTime.of(2026, 8, 26, 4, 30), result.publishedAtUtc());
        assertEquals("JSON_LD_DATEPUBLISHED", result.publishedAtMethod());
        assertTrue(result.text().contains("OpenAI released Model X"));
        assertFalse(result.text().contains("secret"));
    }

    @Test
    void refusesToGuessTimezoneLessPublicationMetadata() {
        String html = """
                <html><head><meta property="article:published_time"
                  content="2026-08-26T12:30:00"></head><body>Body text.</body></html>
                """;

        AiNewsSourceDocumentParser.ParsedDocument result = parser.parse(
                html, "text/html", "https://example.com/story");

        assertNull(result.publishedAtUtc());
        assertNull(result.publishedAtMethod());
    }

    @Test
    void articleJsonLdWinsOverUnrelatedNestedOrganizationDate() {
        String html = """
                <html><head>
                <script type="application/ld+json">
                  {"@type":"WebSite","publisher":{"@type":"Organization",
                   "dateCreated":"2015-01-01T00:00:00Z"}}
                </script>
                <script type="application/ld+json">
                  {"@type":["Thing","NewsArticle"],
                   "datePublished":"2026-08-26T12:30:00+08:00"}
                </script>
                </head><body>Fresh article body.</body></html>
                """;

        AiNewsSourceDocumentParser.ParsedDocument result = parser.parse(
                html, "text/html", "https://example.com/story");

        assertEquals(LocalDateTime.of(2026, 8, 26, 4, 30), result.publishedAtUtc());
        assertEquals("JSON_LD_DATEPUBLISHED", result.publishedAtMethod());
    }

    @Test
    void acceptsTimezoneBearingParselyPublicationMetadata() {
        String html = """
                <html><head><meta name="parsely-pub-date"
                  content="2026-08-26T12:30:00+08:00"></head><body>Body text.</body></html>
                """;

        AiNewsSourceDocumentParser.ParsedDocument result = parser.parse(
                html, "text/html", "https://example.com/story");

        assertEquals(LocalDateTime.of(2026, 8, 26, 4, 30), result.publishedAtUtc());
        assertEquals("HTML_META", result.publishedAtMethod());
    }

    @Test
    void acceptsIsoBasicOffsetUsedByPublisherArticleMetadata() {
        String html = """
                <html><head>
                <meta property="article:published_time" content="2026-08-27T03:20:14+0000">
                <script type="application/ld+json">
                  {"@type":"NewsArticle","datePublished":"2026-08-27T03:20:14+0000"}
                </script>
                </head><body>Publisher article body.</body></html>
                """;

        AiNewsSourceDocumentParser.ParsedDocument result = parser.parse(
                html, "text/html", "https://example.com/story");

        assertEquals(LocalDateTime.of(2026, 8, 27, 3, 20, 14), result.publishedAtUtc());
        assertEquals("2026-08-27T03:20:14+0000", result.publishedAtRaw());
        assertEquals("JSON_LD_DATEPUBLISHED", result.publishedAtMethod());
    }

    @Test
    void canonicalizesTypographyEquivalentQuotesAndInvisibleCharacters() {
        assertEquals("Nvidia's \"new-model\" is ready.",
                AiNewsSourceDocumentParser.normalizeText(
                        "Nvidia\u2019s \u201cnew\u2011model\u201d\u200b is ready."));
    }

    @Test
    void usesConfiguredMainContentExtractorAndReturnsVersionedProvenance() {
        AiNewsMainContentExtractor extractor = (html, url) ->
                new AiNewsContentExtractionResult(
                        "Only the authoritative article body.", "Extractor title",
                        "trafilatura", "2.2.0", "a".repeat(64), false, null);
        AiNewsSourceDocumentParser configured = new AiNewsSourceDocumentParser(
                new ObjectMapper(), extractor);

        AiNewsSourceDocumentParser.ParsedDocument result = configured.parse("""
                <html><head><meta property="article:published_time"
                  content="2026-08-26T12:30:00+08:00"></head>
                <body><nav>Contaminating menu</nav><article>Article body</article></body></html>
                """, "text/html", "https://example.com/story");

        assertEquals("Only the authoritative article body.", result.text());
        assertEquals("Extractor title", result.title());
        assertEquals("trafilatura", result.extractorName());
        assertEquals("2.2.0", result.extractorVersion());
        assertEquals("a".repeat(64), result.extractorConfigHash());
        assertFalse(result.extractionFallback());
    }
}

package vip.newsclaw.news.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsMainContentExtractionServiceTest {

    @Test
    void usesVersionedPrimaryAndEmitsOnlyBoundedOutcomeTags() {
        AiNewsContentExtractionProperties properties = new AiNewsContentExtractionProperties();
        properties.setEnabled(true);
        TrafilaturaContentExtractorClient client = mock(TrafilaturaContentExtractorClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiNewsMainContentExtractionService service =
                new AiNewsMainContentExtractionService(properties, client, registry);
        AiNewsContentExtractionResult expected = new AiNewsContentExtractionResult(
                "Article body", "Title", "trafilatura", "2.2.0", "a".repeat(64), false, null);
        when(client.extract("<html>body</html>", "https://example.com/story"))
                .thenReturn(expected);

        AiNewsContentExtractionResult result = service.extract(
                "<html>body</html>", "https://example.com/story");

        assertEquals(expected, result);
        assertEquals(1.0, registry.get("newsclaw.ai_news.extraction.attempts")
                .tag("outcome", "primary_success").counter().count());
        assertFalse(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .anyMatch(tag -> tag.getValue().contains("example.com")));
    }

    @Test
    void requiredPrimaryFailureFailsClosed() {
        AiNewsContentExtractionProperties properties = new AiNewsContentExtractionProperties();
        properties.setEnabled(true);
        properties.setRequired(true);
        TrafilaturaContentExtractorClient client = mock(TrafilaturaContentExtractorClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiNewsMainContentExtractionService service =
                new AiNewsMainContentExtractionService(properties, client, registry);
        when(client.extract("<html>body</html>", "https://example.com/story"))
                .thenThrow(new AiNewsContentExtractionException("unavailable"));

        assertThrows(AiNewsContentExtractionException.class,
                () -> service.extract("<html>body</html>", "https://example.com/story"));
        assertEquals(1.0, registry.get("newsclaw.ai_news.extraction.attempts")
                .tag("outcome", "primary_failed_closed").counter().count());
    }

    @Test
    void optionalPrimaryFailureUsesExplicitlyMarkedCompatibilityFallback() {
        AiNewsContentExtractionProperties properties = new AiNewsContentExtractionProperties();
        properties.setEnabled(true);
        properties.setRequired(false);
        TrafilaturaContentExtractorClient client = mock(TrafilaturaContentExtractorClient.class);
        AiNewsMainContentExtractionService service = new AiNewsMainContentExtractionService(
                properties, client, new SimpleMeterRegistry());
        when(client.extract(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new AiNewsContentExtractionException("unavailable"));

        AiNewsContentExtractionResult result = service.extract(
                "<html><body><script>secret()</script><p>Article body</p></body></html>",
                "https://example.com/story");

        assertEquals("Article body", result.text());
        assertEquals("jsoup_document_text", result.extractorName());
        assertTrue(result.fallback());
        assertEquals("unavailable", result.warning());
    }

    @Test
    void disabledPrimaryNeverCallsSidecarAndMarksFallback() {
        AiNewsContentExtractionProperties properties = new AiNewsContentExtractionProperties();
        properties.setEnabled(false);
        properties.setRequired(false);
        TrafilaturaContentExtractorClient client = mock(TrafilaturaContentExtractorClient.class);
        AiNewsMainContentExtractionService service = new AiNewsMainContentExtractionService(
                properties, client, new SimpleMeterRegistry());

        AiNewsContentExtractionResult result = service.extract(
                "<html><body><p>Article body</p></body></html>",
                "https://example.com/story");

        assertTrue(result.fallback());
        assertEquals("primary_disabled", result.warning());
        verify(client, never()).extract(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void disabledRequiredPrimaryFailsClosedInsteadOfMasqueradingAsFallback() {
        AiNewsContentExtractionProperties properties = new AiNewsContentExtractionProperties();
        properties.setEnabled(false);
        properties.setRequired(true);
        TrafilaturaContentExtractorClient client = mock(TrafilaturaContentExtractorClient.class);
        AiNewsMainContentExtractionService service = new AiNewsMainContentExtractionService(
                properties, client, new SimpleMeterRegistry());

        assertThrows(AiNewsContentExtractionException.class,
                () -> service.extract("<html><body>Article body</body></html>",
                        "https://example.com/story"));
        verify(client, never()).extract(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}

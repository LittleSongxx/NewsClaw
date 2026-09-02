package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrafilaturaContentExtractorClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void acceptsBoundedVersionedResponse() throws Exception {
        byte[] response = ("{\"text\":\"Only article body\",\"title\":\"Story\","
                + "\"extractorName\":\"trafilatura\",\"extractorVersion\":\"2.2.0\","
                + "\"configHash\":\"" + "a".repeat(64) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        start(response, 200);
        TrafilaturaContentExtractorClient client = client();

        AiNewsContentExtractionResult result = client.extract(
                "<html><body>raw</body></html>", "https://example.com/story");

        assertEquals("Only article body", result.text());
        assertEquals("Story", result.title());
        assertEquals("trafilatura", result.extractorName());
        assertFalse(result.fallback());
    }

    @Test
    void rejectsUnknownOrUnversionedImplementation() throws Exception {
        byte[] response = ("{\"text\":\"body\",\"extractorName\":\"unknown\","
                + "\"extractorVersion\":\"1\",\"configHash\":\"" + "a".repeat(64) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        start(response, 200);

        assertThrows(AiNewsContentExtractionException.class,
                () -> client().extract("<html><body>raw</body></html>",
                        "https://example.com/story"));
    }

    @Test
    void rejectsNonSuccessWithoutSurfacingResponseBody() throws Exception {
        start("{\"error\":\"source secret\"}".getBytes(StandardCharsets.UTF_8), 422);

        AiNewsContentExtractionException error = assertThrows(
                AiNewsContentExtractionException.class,
                () -> client().extract("<html><body>raw</body></html>",
                        "https://example.com/story"));

        assertEquals("正文抽取服务返回 HTTP 422", error.getMessage());
    }

    @Test
    void rejectsUnapprovedVersionEvenWhenProvenanceIsWellFormed() throws Exception {
        byte[] response = ("{\"text\":\"body\",\"extractorName\":\"trafilatura\","
                + "\"extractorVersion\":\"2.3.0\",\"configHash\":\""
                + "a".repeat(64) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        start(response, 200);

        assertThrows(AiNewsContentExtractionException.class,
                () -> client().extract("<html><body>raw</body></html>",
                        "https://example.com/story"));
    }

    @Test
    void rejectsUnapprovedConfigurationHash() throws Exception {
        byte[] response = ("{\"text\":\"body\",\"extractorName\":\"trafilatura\","
                + "\"extractorVersion\":\"2.2.0\",\"configHash\":\""
                + "b".repeat(64) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        start(response, 200);

        assertThrows(AiNewsContentExtractionException.class,
                () -> client().extract("<html><body>raw</body></html>",
                        "https://example.com/story"));
    }

    @Test
    void deploymentDefaultsMatchTheEvaluatedManifestProvenance() throws Exception {
        AiNewsContentExtractionProperties properties = new AiNewsContentExtractionProperties();
        try (var input = getClass().getResourceAsStream(
                "/evals/ai-news/content-extraction/manifest-v1.json")) {
            assertNotNull(input);
            var expected = new ObjectMapper().readTree(input).path("expectedImplementation");
            assertEquals(expected.path("name").asText(), properties.getExpectedName());
            assertEquals(expected.path("version").asText(), properties.getExpectedVersion());
            assertEquals(expected.path("configHash").asText(), properties.getExpectedConfigHash());
        }
    }

    private TrafilaturaContentExtractorClient client() {
        AiNewsContentExtractionProperties properties = new AiNewsContentExtractionProperties();
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setExpectedConfigHash("a".repeat(64));
        properties.setTimeoutMillis(2_000);
        return new TrafilaturaContentExtractorClient(properties, new ObjectMapper());
    }

    private void start(byte[] body, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/extract", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
}

package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BochaAiNewsSearchClientTest {

    @Test
    void reportsExplicitlyDisabledWithoutTouchingTransport() {
        AiNewsCandidatePipelineProperties properties = new AiNewsCandidatePipelineProperties();
        var client = new BochaAiNewsSearchClient(properties, new ObjectMapper(),
                (endpoint, key, json, timeout) -> {
                    throw new AssertionError("disabled provider must not call transport");
                });

        var result = client.collect(Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-28T00:00:00Z"));

        assertFalse(result.enabled());
        assertEquals("bocha-web", result.providerId());
        assertEquals("DISABLED_BY_CONFIG", result.status());
        assertEquals(1, result.snapshots().size());
        assertEquals("DISABLED_BY_CONFIG", result.executions().getFirst().failureMessage());
    }

    @Test
    void sendsOfficialContractAndKeepsProviderLaneRankAndPublicationHint() throws Exception {
        AiNewsCandidatePipelineProperties properties = new AiNewsCandidatePipelineProperties();
        var config = properties.getChinaSearch();
        config.setEnabled(true);
        config.setApiKey("unit-test-secret");
        config.setQueries(List.of("人工智能 模型 最新发布"));
        AtomicReference<String> request = new AtomicReference<>();
        var client = new BochaAiNewsSearchClient(properties, new ObjectMapper(),
                (endpoint, key, json, timeout) -> {
                    assertEquals("https://api.bochaai.com/v1/web-search", endpoint);
                    assertEquals("unit-test-secret", key);
                    request.set(json);
                    return new BochaAiNewsSearchClient.Response(200, """
                            {"code":200,"data":{"webPages":{"value":[
                              {"name":"模型发布","summary":"长摘要","snippet":"短摘要",
                               "url":"https://example.cn/news/1","siteName":"示例媒体",
                               "datePublished":"2026-08-28T08:00:00+08:00"}
                            ]}}}
                            """);
                });

        var result = client.collect(Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-28T00:00:00Z"));

        assertTrue(result.enabled());
        assertEquals("bocha-web", result.providerId());
        assertEquals(1, result.snapshots().size());
        var row = result.snapshots().getFirst().results().getFirst();
        assertEquals(1, row.rank());
        assertEquals("长摘要", row.snippet());
        assertEquals("2026-08-28T08:00:00+08:00", row.publishedAtHint());
        assertEquals(result.providerId(), row.providerId());
        var payload = new ObjectMapper().readTree(request.get());
        assertEquals("人工智能 模型 最新发布", payload.path("query").asText());
        assertEquals("2026-08-27", payload.path("freshness").asText());
        assertTrue(payload.path("summary").asBoolean());
        assertEquals(20, payload.path("count").asInt());
        assertFalse(request.get().contains("unit-test-secret"));
    }
}

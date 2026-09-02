package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.newsclaw.news.model.AiNewsDiscoveryRunEntity;
import vip.newsclaw.news.repository.AiNewsDiscoveryRunMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiNewsDiscoveryRunLedgerTest {

    @Test
    void persistsReplayPayloadAndSummaryWithoutSecrets() throws Exception {
        AiNewsDiscoveryRunMapper mapper = mock(AiNewsDiscoveryRunMapper.class);
        doAnswer(invocation -> {
            AiNewsDiscoveryRunEntity entity = invocation.getArgument(0);
            entity.setId(7001L);
            return 1;
        }).when(mapper).insert(any(AiNewsDiscoveryRunEntity.class));
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AiNewsDiscoveryRunLedger ledger = new AiNewsDiscoveryRunLedger(mapper, objectMapper);
        var row = new AiNewsDiscoverySearchService.SnapshotResult(1, "title",
                "https://example.com/story", "snippet", "2026-08-27T10:00:00Z",
                "example.com", "tavily", 0.9D);
        var querySnapshot = new AiNewsDiscoverySearchService.QuerySnapshot(
                "open_web", "tavily", false, "a".repeat(64), "AI news", "news",
                LocalDate.parse("2026-08-26"), LocalDate.parse("2026-08-29"),
                List.of(), List.of(row));
        var batch = new AiNewsDiscoverySearchService.DiscoveryBatch(
                "untrusted_fused_news_candidates", false,
                "2026-08-27T02:00:00Z", "2026-08-28T02:00:00Z", 1, 1,
                List.of(), List.of(new AiNewsDiscoverySearchService.QueryExecution(
                        "open_web", "tavily", 1, "", "news",
                        LocalDate.parse("2026-08-26"), LocalDate.parse("2026-08-29"),
                        List.of(), false, "a".repeat(64))), 0, "capture required",
                "2026-08-28T01:00:00Z", "policy-v2", "b".repeat(64),
                "c".repeat(64), Map.of("selectedCandidates", 0),
                List.of(querySnapshot), null, false);

        assertEquals(7001L, ledger.persist(42L, "artificial intelligence", 30, batch));

        ArgumentCaptor<AiNewsDiscoveryRunEntity> saved =
                ArgumentCaptor.forClass(AiNewsDiscoveryRunEntity.class);
        verify(mapper).insert(saved.capture());
        assertEquals(42L, saved.getValue().getWorkspaceId());
        assertEquals(1, saved.getValue().getSuccessfulQueryCount());
        assertEquals(0, saved.getValue().getCachedQueryCount());
        var json = objectMapper.readTree(saved.getValue().getSnapshotJson());
        assertEquals("https://example.com/story",
                json.path("querySnapshots").get(0).path("results").get(0).path("url").asText());
        assertTrue(!saved.getValue().getSnapshotJson().toLowerCase().contains("api_key"));
    }
}

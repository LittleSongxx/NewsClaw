package vip.newsclaw.news.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEventUpsertRequest;
import vip.newsclaw.news.service.AiNewsDiscoverySearchService;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.service.AiNewsSourceCaptureService;
import vip.newsclaw.news.service.OfficialSourceEvidenceCaptureService;
import vip.newsclaw.workspace.conversation.ConversationService;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsEventToolCaptureContractTest {

    private static final org.springframework.ai.chat.model.ToolContext SYSTEM_CONTEXT =
            ChatOrigin.cron("test-conversation", 1L, null, null, null).toToolContext();

    @Test
    void agentUpsertUsesStrictCaptureAndWindowEntryPoint() {
        AiNewsEventService events = mock(AiNewsEventService.class);
        AiNewsSourceCaptureService captures = mock(AiNewsSourceCaptureService.class);
        when(events.upsertCaptured(eq(1L), any(), any(), any()))
                .thenReturn(new AiNewsEventEntity());
        AiNewsEventTool tool = tool(events, captures);

        String output = upsert(tool, "900", "2026-08-26T03:00:00Z",
                "2026-08-27T03:00:00Z");

        assertTrue(!output.startsWith("Error:"), output);
        ArgumentCaptor<AiNewsEventUpsertRequest> request =
                ArgumentCaptor.forClass(AiNewsEventUpsertRequest.class);
        verify(events).upsertCaptured(eq(1L), request.capture(),
                eq(Instant.parse("2026-08-26T03:00:00Z")),
                eq(Instant.parse("2026-08-27T03:00:00Z")));
        assertEquals(900L, request.getValue().evidence().getFirst().captureId());
        assertEquals(null, request.getValue().evidence().getFirst().sourceUrl());
    }

    @Test
    void agentUpsertFailsBeforeServiceWhenCaptureIdIsMissing() {
        AiNewsEventService events = mock(AiNewsEventService.class);
        AiNewsEventTool tool = tool(events, mock(AiNewsSourceCaptureService.class));

        String output = upsert(tool, null, "2026-08-26T03:00:00Z",
                "2026-08-27T03:00:00Z");

        assertTrue(output.startsWith("Error: captureId is required"), output);
        verify(events, never()).upsertCaptured(any(), any(), any(), any());
    }

    @Test
    void agentUpsertFailsBeforeServiceWhenFrozenWindowIsMissing() {
        AiNewsEventService events = mock(AiNewsEventService.class);
        AiNewsEventTool tool = tool(events, mock(AiNewsSourceCaptureService.class));

        String output = upsert(tool, "900", null, "2026-08-27T03:00:00Z");

        assertTrue(output.startsWith("Error: windowStart is required"), output);
        verify(events, never()).upsertCaptured(any(), any(), any(), any());
    }

    @Test
    void captureSourceDelegatesToServerOwnedCaptureService() {
        AiNewsSourceCaptureService captures = mock(AiNewsSourceCaptureService.class);
        AiNewsEventTool tool = tool(mock(AiNewsEventService.class), captures);

        String output = tool.ai_news_event(
                "capture_source", null, null, null, null,
                "https://openai.com/index/model-x", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, SYSTEM_CONTEXT);

        assertEquals("null", output);
        verify(captures).capture(1L, "https://openai.com/index/model-x");
    }

    @Test
    void discoverCompactsOversizedProviderMetadataBelowTheInlineSpillBoundary() throws Exception {
        AiNewsDiscoverySearchService discovery = mock(AiNewsDiscoverySearchService.class);
        List<AiNewsDiscoverySearchService.DiscoveryCandidate> candidates = IntStream.range(0, 30)
                .mapToObj(index -> new AiNewsDiscoverySearchService.DiscoveryCandidate(index + 1,
                        "Long but plausible AI news title " + index + " " + "x".repeat(120),
                        "https://publisher.example/news/" + index + "/" + "path".repeat(30),
                        "publisher.example", "2026-08-26T10:00:00Z", 0.02D, 0.8D,
                        index % 3 == 0, index % 3 == 1, List.of("lane-" + index),
                        "untrusted discovery snippet ".repeat(30)))
                .toList();
        List<AiNewsDiscoverySearchService.QueryExecution> executions = IntStream.range(0, 10)
                .mapToObj(index -> new AiNewsDiscoverySearchService.QueryExecution("lane-" + index,
                        "tavily", 20, "", "large query " + "q".repeat(100),
                        java.time.LocalDate.parse("2026-08-25"),
                        java.time.LocalDate.parse("2026-08-28"),
                        IntStream.range(0, 20).mapToObj(i -> "domain-" + i + ".example").toList()))
                .toList();
        when(discovery.discover(eq(1L), eq("artificial intelligence"), any(), any(), eq(30)))
                .thenReturn(new AiNewsDiscoverySearchService.DiscoveryBatch(
                        "untrusted_fused_news_candidates", false,
                        "2026-08-26T03:15:40Z", "2026-08-27T03:15:40Z",
                        10, 30, candidates, executions, 4, "capture each URL before use"));
        AiNewsEventTool tool = tool(mock(AiNewsEventService.class),
                mock(AiNewsSourceCaptureService.class));
        tool.setDiscoverySearchService(discovery);

        String output = discover(tool);

        assertTrue(output.length() <= 7_800, "compact discovery must stay inline: " + output.length());
        com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper().readTree(output);
        assertEquals(30, json.path("uniqueUrlCount").asInt());
        assertEquals(10, json.path("successfulQueryCount").asInt());
        assertEquals(4, json.path("structuredSourceCount").asInt());
        assertEquals(0, json.path("failedExecutions").size());
        assertTrue(json.path("truncatedForInlineBudget").asBoolean());
        assertTrue(json.path("returnedCandidateCount").asInt() < 30);
        assertEquals("official", json.path("candidates").get(0).path("sourceClass").asText());
        assertEquals("2026-08-26T10:00:00Z",
                json.path("candidates").get(0).path("publishedAtHint").asText());
        assertTrue(!output.contains("snippet"));
        assertTrue(!output.contains("requestedIncludeDomains"));
    }

    private static AiNewsEventTool tool(AiNewsEventService events,
                                        AiNewsSourceCaptureService captures) {
        return new AiNewsEventTool(events, mock(OfficialSourceEvidenceCaptureService.class),
                captures, mock(ConversationService.class), new ObjectMapper());
    }

    private static String upsert(AiNewsEventTool tool, String captureId,
                                 String windowStart, String windowEnd) {
        return tool.ai_news_event(
                "upsert", null, "Model X 发布", "摘要", "model", null,
                null, null, "OpenAI released Model X to developers worldwide.",
                "OpenAI released Model X to developers worldwide.", "entails", "0.95",
                "OpenAI", null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                captureId, null, windowStart, windowEnd, SYSTEM_CONTEXT);
    }

    private static String discover(AiNewsEventTool tool) {
        return tool.ai_news_event(
                "discover", null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "artificial intelligence", null, null, "30", null, null,
                null, null, "2026-08-26T03:15:40Z", "2026-08-27T03:15:40Z", SYSTEM_CONTEXT);
    }
}

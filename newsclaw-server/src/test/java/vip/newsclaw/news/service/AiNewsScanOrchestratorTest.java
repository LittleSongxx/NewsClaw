package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import vip.newsclaw.news.model.AiNewsScanRunEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsScanOrchestratorTest {

    private final AiNewsCandidatePipelineProperties properties =
            new AiNewsCandidatePipelineProperties();
    private final AiNewsCandidatePipelineService pipeline =
            mock(AiNewsCandidatePipelineService.class);
    private final AiNewsStructuredIngestionService ingestion =
            mock(AiNewsStructuredIngestionService.class);
    private final AiNewsDiscoverySearchService discovery =
            mock(AiNewsDiscoverySearchService.class);
    private final AiNewsDiscoveryRunLedger discoveryLedger =
            mock(AiNewsDiscoveryRunLedger.class);
    private final BochaAiNewsSearchClient china = mock(BochaAiNewsSearchClient.class);
    private final AiNewsCandidateCaptureWorker worker = mock(AiNewsCandidateCaptureWorker.class);
    private AiNewsScanOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setCaptureEnabled(false);
        orchestrator = new AiNewsScanOrchestrator(properties, pipeline, ingestion, discovery,
                discoveryLedger, china, worker);
    }

    @Test
    void backendOwnsTheWholeScanAndPersistsTheGlobalChinaUnion() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-28T00:00:00Z");
        AiNewsScanRunEntity run = run(10L, 7L);
        var global = batch(start, end, List.of(snapshot("global", "tavily")), List.of(
                execution("global", "tavily", "")));
        var disabledChina = new BochaAiNewsSearchClient.CollectionResult(false,
                "bocha-web", "DISABLED_MISSING_CREDENTIAL",
                List.of(snapshot("china_web_disabled", "bocha-web")),
                List.of(execution("china_web_disabled", "bocha-web",
                        "DISABLED_MISSING_CREDENTIAL")));
        when(pipeline.startOrReuseScan(any(), any(), any(), eq(start), eq(end), any(), anyString()))
                .thenReturn(new AiNewsCandidatePipelineService.ScanStart(run, false));
        when(ingestion.persistentMainlineEnabled()).thenReturn(false);
        when(discovery.discoverUnpersisted(7L, "AI", start, end, 30)).thenReturn(global);
        when(china.collect(start, end)).thenReturn(disabledChina);
        when(discovery.replay(eq("AI"), any(), eq(30)))
                .thenAnswer(call -> call.getArgument(1));
        when(discoveryLedger.persist(eq(7L), eq("AI"), eq(30), any())).thenReturn(900L);
        when(pipeline.inspectRun(7L, 10L)).thenReturn(summary(run));

        var result = orchestrator.run(7L, "AI", start, end, 30, "manual");

        assertEquals(10L, result.run().getId());
        InOrder order = inOrder(pipeline, discovery, china, discoveryLedger, worker);
        order.verify(pipeline).startOrReuseScan(eq(7L), eq("manual"), eq("AI"), eq(start), eq(end),
                eq(properties.getConfigVersion()), anyString());
        order.verify(discovery).discoverUnpersisted(7L, "AI", start, end, 30);
        order.verify(china).collect(start, end);
        order.verify(discovery).replay(eq("AI"), any(), eq(30));
        order.verify(discoveryLedger).persist(eq(7L), eq("AI"), eq(30), any());
        order.verify(pipeline).persistDiscovery(eq(10L),
                org.mockito.ArgumentMatchers.argThat(batch ->
                batch.snapshotPersisted()
                                && batch.discoveryRunId().equals(900L)
                                && batch.querySnapshots().size() == 2
                                && batch.executions().size() == 2
                                && batch.executions().stream().filter(item ->
                                "china_web_disabled".equals(item.family())).count() == 1
                                && batch.executions().stream().anyMatch(item ->
                                "DISABLED_MISSING_CREDENTIAL".equals(item.failureMessage()))));
        order.verify(worker).run(10L, properties.getMaxCapturesPerScan());
        order.verify(pipeline).completeScan(10L);
    }

    @Test
    void terminalFailureIsWrittenWhenDiscoveryFails() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-28T00:00:00Z");
        AiNewsScanRunEntity run = run(11L, 7L);
        RuntimeException failure = new RuntimeException("provider unavailable");
        when(pipeline.startOrReuseScan(any(), any(), any(), eq(start), eq(end), any(), anyString()))
                .thenReturn(new AiNewsCandidatePipelineService.ScanStart(run, false));
        when(ingestion.persistentMainlineEnabled()).thenReturn(false);
        when(discovery.discoverUnpersisted(7L, "AI", start, end, 30)).thenThrow(failure);

        assertThrows(RuntimeException.class,
                () -> orchestrator.run(7L, "AI", start, end, 30, "manual"));

        verify(pipeline).failScan(11L, failure);
        verify(worker, never()).run(any(), anyInt());
        verify(pipeline, never()).completeScan(any());
    }

    @Test
    void allProviderFailuresCannotProduceAFreshCompletedScan() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-28T00:00:00Z");
        AiNewsScanRunEntity run = run(12L, 7L);
        var failedGlobal = batch(start, end, List.of(snapshot("global", "tavily")), List.of(
                execution("global", "tavily", "HTTP_503")));
        var failedChina = new BochaAiNewsSearchClient.CollectionResult(false,
                "bocha-web", "HTTP_502",
                List.of(snapshot("china", "bocha-web")),
                List.of(execution("china", "bocha-web", "HTTP_502")));
        when(pipeline.startOrReuseScan(any(), any(), any(), eq(start), eq(end), any(), anyString()))
                .thenReturn(new AiNewsCandidatePipelineService.ScanStart(run, false));
        when(ingestion.persistentMainlineEnabled()).thenReturn(false);
        when(discovery.discoverUnpersisted(7L, "AI", start, end, 30)).thenReturn(failedGlobal);
        when(china.collect(start, end)).thenReturn(failedChina);
        when(discovery.replay(eq("AI"), any(), eq(30))).thenAnswer(call -> call.getArgument(1));
        when(discoveryLedger.persist(eq(7L), eq("AI"), eq(30), any())).thenReturn(901L);

        var error = assertThrows(vip.newsclaw.exception.NewsClawException.class,
                () -> orchestrator.run(7L, "AI", start, end, 30, "manual"));

        assertEquals(503, error.getCode());
        verify(pipeline).persistDiscovery(eq(12L), any());
        verify(pipeline).failScan(12L, error);
        verify(worker, never()).run(any(), anyInt());
        verify(pipeline, never()).completeScan(any());
    }

    @Test
    void identicalWindowReusesDurableRunWithoutPayingProvidersAgain() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-28T00:00:00Z");
        AiNewsScanRunEntity run = run(13L, 7L);
        run.setRunStatus("COMPLETED");
        var summary = summary(run);
        when(pipeline.startOrReuseScan(any(), any(), any(), eq(start), eq(end), any(), anyString()))
                .thenReturn(new AiNewsCandidatePipelineService.ScanStart(run, true));
        when(pipeline.inspectRun(7L, 13L)).thenReturn(summary);

        assertEquals(summary, orchestrator.run(7L, "AI", start, end, 30, "agent"));

        verify(discovery, never()).discoverUnpersisted(any(), any(), any(), any(), any());
        verify(china, never()).collect(any(), any());
        verify(worker, never()).run(any(), anyInt());
    }

    private static AiNewsScanRunEntity run(long id, long workspaceId) {
        AiNewsScanRunEntity run = new AiNewsScanRunEntity();
        run.setId(id);
        run.setWorkspaceId(workspaceId);
        run.setTopic("AI");
        return run;
    }

    private static AiNewsDiscoverySearchService.DiscoveryBatch batch(
            Instant start,
            Instant end,
            List<AiNewsDiscoverySearchService.QuerySnapshot> snapshots,
            List<AiNewsDiscoverySearchService.QueryExecution> executions) {
        return new AiNewsDiscoverySearchService.DiscoveryBatch(
                "untrusted", false, start.toString(), end.toString(), 1, 0,
                List.of(), executions, 0, "hints", end.toString(), "policy",
                "snapshot", "ranking", Map.of(), snapshots, null, false);
    }

    private static AiNewsDiscoverySearchService.QuerySnapshot snapshot(
            String lane, String provider) {
        return new AiNewsDiscoverySearchService.QuerySnapshot(lane, provider, false, "hash",
                lane, "news", LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 28),
                List.of(), List.of());
    }

    private static AiNewsDiscoverySearchService.QueryExecution execution(
            String lane, String provider, String failure) {
        return new AiNewsDiscoverySearchService.QueryExecution(lane, provider, 0, failure,
                lane, LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 28),
                List.of(), false, "hash");
    }

    private static AiNewsCandidatePipelineService.RunSummary summary(AiNewsScanRunEntity run) {
        var empty = new AiNewsCandidatePipelineService.Metric("empty", 0, 0, null, "empty");
        var scorecard = new AiNewsCandidatePipelineService.Scorecard(
                empty, empty, empty, empty, 0);
        return new AiNewsCandidatePipelineService.RunSummary(run, List.of(), scorecard,
                new ObjectMapper().createObjectNode());
    }
}

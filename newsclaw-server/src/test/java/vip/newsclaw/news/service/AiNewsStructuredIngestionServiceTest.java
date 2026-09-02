package vip.newsclaw.news.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.newsclaw.news.model.AiNewsIngestionRunEntity;
import vip.newsclaw.news.model.AiNewsSourceEndpointEntity;
import vip.newsclaw.news.source.NewsSourceChannel;
import vip.newsclaw.news.source.NewsSourceEndpointDescriptor;
import vip.newsclaw.news.source.NewsSourcePollBatch;
import vip.newsclaw.news.source.NewsSourceValidators;
import vip.newsclaw.news.source.ScheduledNewsSourceProvider;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiNewsStructuredIngestionServiceTest {

    @Mock
    private ScheduledNewsSourceProvider provider;
    @Mock
    private AiNewsIngestionLedgerService ledger;
    @Mock
    private AiNewsIngestionMetrics metrics;

    private AiNewsIngestionProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AiNewsIngestionProperties();
        properties.setEnabled(true);
        properties.setMaxPollsPerCycle(50);
    }

    @Test
    void isolatesProviderFailureAndCompletesEveryAttemptInTheLedger() {
        NewsSourceEndpointDescriptor healthy = descriptor("healthy");
        NewsSourceEndpointDescriptor broken = descriptor("broken");
        AiNewsSourceEndpointEntity healthyRow = endpoint(1L, healthy);
        AiNewsSourceEndpointEntity brokenRow = endpoint(2L, broken);
        AiNewsIngestionRunEntity healthyRun = run(11L, healthyRow);
        AiNewsIngestionRunEntity brokenRun = run(12L, brokenRow);

        when(provider.providerId()).thenReturn("rss");
        when(provider.configuredEndpoints()).thenReturn(List.of(healthy, broken));
        when(ledger.abandonStaleRuns(any())).thenReturn(1);
        when(ledger.reconcileProvider("rss", List.of(healthy, broken)))
                .thenReturn(List.of(healthyRow, brokenRow));
        when(ledger.isDue(any(), any())).thenReturn(true);
        when(ledger.claimDue(any(), any(), any())).thenReturn(true);
        when(ledger.validators(any())).thenReturn(NewsSourceValidators.EMPTY);
        when(ledger.startRun(healthyRow, healthy, "scheduled")).thenReturn(healthyRun);
        when(ledger.startRun(brokenRow, broken, "scheduled")).thenReturn(brokenRun);

        Instant started = Instant.parse("2026-08-27T12:00:00Z");
        NewsSourcePollBatch successful = new NewsSourcePollBatch(healthy,
                NewsSourcePollBatch.Status.SUCCESS, started, started.plusMillis(25),
                List.of(), List.of(), "", "");
        when(provider.poll(eq(healthy), any())).thenReturn(successful);
        when(provider.poll(eq(broken), any())).thenThrow(new IllegalStateException("publisher down"));
        when(ledger.completeRun(eq(healthyRun), eq(healthyRow), eq(successful)))
                .thenReturn(completion(healthyRun, "success"));
        when(ledger.completeRun(eq(brokenRun), eq(brokenRow), any()))
                .thenAnswer(invocation -> {
                    NewsSourcePollBatch failed = invocation.getArgument(2);
                    assertEquals(NewsSourcePollBatch.Status.FAILED, failed.status());
                    assertEquals("PROVIDER_ERROR", failed.errorCode());
                    return completion(brokenRun, "failed");
                });

        var summary = service(provider).runDueCycle("scheduled");

        assertTrue(summary.enabled());
        assertEquals(2, summary.configuredEndpoints());
        assertEquals(2, summary.attemptedEndpoints());
        assertEquals(1, summary.succeededEndpoints());
        assertEquals(0, summary.degradedEndpoints());
        assertEquals(1, summary.failedEndpoints());
        assertEquals(1, summary.abandonedRuns());
        assertEquals(1, summary.errors().size());
        assertTrue(summary.errors().getFirst().contains("broken"));
        verify(metrics).recordRun(successful, "scheduled", 0, 0, 0);
        verify(metrics, times(2)).recordRun(any(NewsSourcePollBatch.class), eq("scheduled"),
                eq(0), eq(0), eq(0));
        verify(ledger, never()).markPersistenceFailure(any(), any(), any());
    }

    @Test
    void persistenceFailureIsClosedAndDoesNotAbortLaterEndpoints() {
        NewsSourceEndpointDescriptor first = descriptor("first");
        NewsSourceEndpointDescriptor second = descriptor("second");
        AiNewsSourceEndpointEntity firstRow = endpoint(1L, first);
        AiNewsSourceEndpointEntity secondRow = endpoint(2L, second);
        AiNewsIngestionRunEntity firstRun = run(11L, firstRow);
        AiNewsIngestionRunEntity secondRun = run(12L, secondRow);
        Instant started = Instant.parse("2026-08-27T12:00:00Z");
        NewsSourcePollBatch firstBatch = new NewsSourcePollBatch(first,
                NewsSourcePollBatch.Status.SUCCESS, started, started.plusMillis(10),
                List.of(), List.of(), "", "");
        NewsSourcePollBatch secondBatch = new NewsSourcePollBatch(second,
                NewsSourcePollBatch.Status.SUCCESS, started, started.plusMillis(20),
                List.of(), List.of(), "", "");

        when(provider.providerId()).thenReturn("rss");
        when(provider.configuredEndpoints()).thenReturn(List.of(first, second));
        when(ledger.reconcileProvider("rss", List.of(first, second)))
                .thenReturn(List.of(firstRow, secondRow));
        when(ledger.isDue(any(), any())).thenReturn(true);
        when(ledger.claimDue(any(), any(), any())).thenReturn(true);
        when(ledger.validators(any())).thenReturn(NewsSourceValidators.EMPTY);
        when(ledger.startRun(firstRow, first, "scheduled")).thenReturn(firstRun);
        when(ledger.startRun(secondRow, second, "scheduled")).thenReturn(secondRun);
        when(provider.poll(first, NewsSourceValidators.EMPTY)).thenReturn(firstBatch);
        when(provider.poll(second, NewsSourceValidators.EMPTY)).thenReturn(secondBatch);
        when(ledger.completeRun(firstRun, firstRow, firstBatch))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(ledger.completeRun(secondRun, secondRow, secondBatch))
                .thenReturn(completion(secondRun, "success"));

        var summary = service(provider).runDueCycle("scheduled");

        assertEquals(2, summary.attemptedEndpoints());
        assertEquals(1, summary.succeededEndpoints());
        assertEquals(1, summary.failedEndpoints());
        verify(ledger).markPersistenceFailure(eq(firstRun), eq(firstRow), any(Exception.class));
        verify(metrics).recordTerminalFailure(first, "scheduled", "persistence_failed");
        verify(ledger).completeRun(secondRun, secondRow, secondBatch);
        verify(metrics).recordRun(secondBatch, "scheduled", 0, 0, 0);
    }

    @Test
    void disabledMainlineIsACompleteNoOp() {
        properties.setEnabled(false);
        when(provider.providerId()).thenReturn("rss");

        var summary = service(provider).runDueCycle("scheduled");

        assertTrue(!summary.enabled());
        assertEquals(0, summary.attemptedEndpoints());
        verifyNoInteractions(ledger, metrics);
        verify(provider, never()).configuredEndpoints();
    }

    @Test
    void emptyPersistentStoreDoesNotPollFromARequestByDefault() {
        Instant since = Instant.now().minusSeconds(3600);
        when(ledger.recentResults(any(Instant.class), eq(20))).thenReturn(List.of());

        assertEquals(List.of(), service().recentCandidates(since, 20, true));

        verify(ledger).recentResults(any(Instant.class), eq(20));
        verify(ledger, never()).abandonStaleRuns(any());
        verify(provider, never()).configuredEndpoints();
    }

    private AiNewsStructuredIngestionService service(ScheduledNewsSourceProvider... providers) {
        return new AiNewsStructuredIngestionService(List.of(providers), ledger, metrics, properties);
    }

    private static NewsSourceEndpointDescriptor descriptor(String key) {
        return new NewsSourceEndpointDescriptor(key, 1, "source-" + key, "rss",
                NewsSourceChannel.FEED, "FEED", URI.create("https://example.com/" + key),
                List.of("en"), List.of("model"), 900, false,
                "review_required", "metadata_only", "review_required");
    }

    private static AiNewsSourceEndpointEntity endpoint(Long id,
                                                        NewsSourceEndpointDescriptor descriptor) {
        AiNewsSourceEndpointEntity row = new AiNewsSourceEndpointEntity();
        row.setId(id);
        row.setEndpointKey(descriptor.endpointKey());
        row.setEnabled(true);
        return row;
    }

    private static AiNewsIngestionRunEntity run(Long id, AiNewsSourceEndpointEntity endpoint) {
        AiNewsIngestionRunEntity run = new AiNewsIngestionRunEntity();
        run.setId(id);
        run.setEndpointId(endpoint.getId());
        return run;
    }

    private static AiNewsIngestionLedgerService.Completion completion(
            AiNewsIngestionRunEntity run, String status) {
        return new AiNewsIngestionLedgerService.Completion(run.getId(), 0, 0, 0,
                0, 0, 0L, status);
    }
}

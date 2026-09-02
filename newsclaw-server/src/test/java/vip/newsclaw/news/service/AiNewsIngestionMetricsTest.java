package vip.newsclaw.news.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vip.newsclaw.news.source.NewsSourceChannel;
import vip.newsclaw.news.source.NewsSourceEndpointDescriptor;
import vip.newsclaw.news.source.NewsSourcePollBatch;
import vip.newsclaw.news.source.NewsSourceTransportRecord;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiNewsIngestionMetricsTest {

    @Test
    void recordsBoundedStageMetricsWithoutEndpointOrUrlCardinality() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        AiNewsIngestionMetrics metrics = new AiNewsIngestionMetrics(provider);
        NewsSourceEndpointDescriptor endpoint = new NewsSourceEndpointDescriptor(
                "secret-looking-endpoint", 1, "source", "rss", NewsSourceChannel.FEED,
                "FEED", URI.create("https://publisher.example/feed?token=never-a-metric-tag"),
                List.of("en"), List.of("model"), 900, false,
                "review_required", "metadata_only", "review_required");
        Instant started = Instant.parse("2026-08-27T12:00:00Z");
        NewsSourceTransportRecord transport = new NewsSourceTransportRecord(
                endpoint.url(), endpoint.url(), 200, "application/rss+xml", "", "", "",
                3L, new byte[]{1, 2, 3}, false, false, started,
                started.plusMillis(20), "", "");
        NewsSourcePollBatch batch = new NewsSourcePollBatch(endpoint,
                NewsSourcePollBatch.Status.SUCCESS, started, started.plusMillis(25),
                List.of(), List.of(transport), "", "");

        metrics.recordRun(batch, "scheduled", 2, 1, 3);
        metrics.recordTerminalFailure(endpoint, "scheduled", "persistence_failed");

        assertEquals(1D, registry.get("newsclaw.ai_news.ingestion.runs")
                .tags("channel", "feed", "outcome", "success", "trigger", "scheduled")
                .counter().count());
        assertEquals(2D, registry.get("newsclaw.ai_news.ingestion.items")
                .tags("channel", "feed", "outcome", "new_item").counter().count());
        assertEquals(1D, registry.get("newsclaw.ai_news.ingestion.transports")
                .tags("channel", "feed", "status_family", "2xx", "outcome", "success")
                .counter().count());
        assertEquals(3D, registry.get("newsclaw.ai_news.ingestion.bytes")
                .tag("channel", "feed").counter().count());
        assertEquals(1D, registry.get("newsclaw.ai_news.ingestion.runs")
                .tags("channel", "feed", "outcome", "persistence_failed",
                        "trigger", "scheduled").counter().count());
        assertFalse(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .anyMatch(tag -> "endpoint".equals(tag.getKey()) || "url".equals(tag.getKey())
                        || tag.getValue().contains("publisher.example")));
    }
}

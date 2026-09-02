package vip.newsclaw.news.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vip.newsclaw.news.source.NewsSourcePollBatch;
import vip.newsclaw.news.source.NewsSourceEndpointDescriptor;
import vip.newsclaw.news.source.NewsSourceTransportRecord;

import java.time.Duration;
import java.util.Locale;

/** Low-cardinality metrics gateway for the structured-source ingestion pipeline. */
@Component
public class AiNewsIngestionMetrics {

    private final MeterRegistry registry;

    public AiNewsIngestionMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable();
    }

    public void recordRun(NewsSourcePollBatch batch, String triggerType,
                          int newItems, int newVersions, int unchangedItems) {
        if (registry == null || batch == null) return;
        String channel = boundedTag(batch.endpoint().channel().name());
        String outcome = boundedTag(batch.status().name());
        String trigger = triggerTag(triggerType);
        registry.counter("newsclaw.ai_news.ingestion.runs",
                "channel", channel, "outcome", outcome, "trigger", trigger).increment();
        Timer.builder("newsclaw.ai_news.ingestion.duration")
                .tags("channel", channel, "outcome", outcome, "trigger", trigger)
                .register(registry)
                .record(Duration.between(batch.startedAt(), batch.finishedAt()));
        increment("newsclaw.ai_news.ingestion.items", channel, "new_item", newItems);
        increment("newsclaw.ai_news.ingestion.items", channel, "new_version", newVersions);
        increment("newsclaw.ai_news.ingestion.items", channel, "unchanged", unchangedItems);
        for (NewsSourceTransportRecord transport : batch.transports()) {
            registry.counter("newsclaw.ai_news.ingestion.transports",
                    "channel", channel, "status_family", statusFamily(transport.httpStatus()),
                    "outcome", transport.succeeded() ? "success" : "failure").increment();
            if (transport.receivedBytes() > 0) {
                registry.counter("newsclaw.ai_news.ingestion.bytes",
                        "channel", channel).increment(transport.receivedBytes());
            }
        }
    }

    /** Records failures that prevent an otherwise started poll from being committed. */
    public void recordTerminalFailure(NewsSourceEndpointDescriptor endpoint,
                                      String triggerType, String outcome) {
        if (registry == null || endpoint == null) return;
        registry.counter("newsclaw.ai_news.ingestion.runs",
                "channel", boundedTag(endpoint.channel().name()),
                "outcome", boundedTag(outcome),
                "trigger", triggerTag(triggerType)).increment();
    }

    private void increment(String name, String channel, String outcome, int value) {
        if (value <= 0) return;
        registry.counter(name, "channel", channel, "outcome", outcome).increment(value);
    }

    private static String statusFamily(Integer status) {
        return status == null || status < 100 ? "none" : status / 100 + "xx";
    }

    private static String triggerTag(String value) {
        String normalized = boundedTag(value);
        return switch (normalized) {
            case "scheduled", "on_demand", "manual", "recovery" -> normalized;
            default -> "other";
        };
    }

    private static String boundedTag(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_");
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }
}

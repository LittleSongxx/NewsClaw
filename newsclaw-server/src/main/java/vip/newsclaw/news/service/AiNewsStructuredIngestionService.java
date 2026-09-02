package vip.newsclaw.news.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.newsclaw.news.model.AiNewsIngestionRunEntity;
import vip.newsclaw.news.model.AiNewsSourceEndpointEntity;
import vip.newsclaw.news.source.NewsSourceEndpointDescriptor;
import vip.newsclaw.news.source.NewsSourcePollBatch;
import vip.newsclaw.news.source.NewsSourceResult;
import vip.newsclaw.news.source.ScheduledNewsSourceProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Orchestrates endpoint reconciliation, due polling and request-time ledger reads. */
@Service
@Slf4j
public class AiNewsStructuredIngestionService {

    private final List<ScheduledNewsSourceProvider> providers;
    private final AiNewsIngestionLedgerService ledger;
    private final AiNewsIngestionMetrics metrics;
    private final AiNewsIngestionProperties properties;

    public AiNewsStructuredIngestionService(List<ScheduledNewsSourceProvider> providers,
                                            AiNewsIngestionLedgerService ledger,
                                            AiNewsIngestionMetrics metrics,
                                            AiNewsIngestionProperties properties) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.ledger = ledger;
        this.metrics = metrics;
        this.properties = properties;
        Map<String, ScheduledNewsSourceProvider> unique = new LinkedHashMap<>();
        for (ScheduledNewsSourceProvider provider : this.providers) {
            String providerId = provider == null ? null : provider.providerId();
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalStateException("scheduled news source provider id is required");
            }
            ScheduledNewsSourceProvider old = unique.putIfAbsent(providerId, provider);
            if (old != null) {
                throw new IllegalStateException("duplicate scheduled news source provider: "
                        + providerId);
            }
        }
    }

    public boolean persistentMainlineEnabled() {
        return properties.isEnabled();
    }

    public CycleSummary runDueCycle(String triggerType) {
        if (!properties.isEnabled()) return CycleSummary.disabled();
        int staleMinutes = Math.min(Math.max(1, properties.getStaleRunMinutes()), 1440);
        Duration claimLease = Duration.ofMinutes(staleMinutes);
        int stale = ledger.abandonStaleRuns(claimLease);
        int configured = 0;
        int attempted = 0;
        int succeeded = 0;
        int degraded = 0;
        int failed = 0;
        int newItems = 0;
        int newVersions = 0;
        List<String> errors = new ArrayList<>();
        int maxPolls = Math.min(Math.max(properties.getMaxPollsPerCycle(), 1), 500);
        Instant now = Instant.now();

        for (int providerIndex = 0; providerIndex < providers.size(); providerIndex++) {
            ScheduledNewsSourceProvider provider = providers.get(providerIndex);
            // Reserve a deterministic slice for every provider. A hot RSS
            // endpoint must not consume the whole cycle and indefinitely
            // starve later official/Chinese channels.
            int providerBudget = fairBudget(providerIndex, providers.size(), maxPolls);
            int providerAttempted = 0;
            List<NewsSourceEndpointDescriptor> descriptors;
            List<AiNewsSourceEndpointEntity> rows;
            try {
                List<NewsSourceEndpointDescriptor> configuredDescriptors =
                        provider.configuredEndpoints();
                descriptors = configuredDescriptors == null
                        ? List.of() : List.copyOf(configuredDescriptors);
                rows = ledger.reconcileProvider(provider.providerId(), descriptors);
            } catch (Exception e) {
                failed++;
                errors.add(provider.providerId() + ": reconcile: " + safe(e.getMessage()));
                continue;
            }
            configured += rows.size();
            Map<String, NewsSourceEndpointDescriptor> byKey = descriptors.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            NewsSourceEndpointDescriptor::endpointKey,
                            value -> value, (left, right) -> left, LinkedHashMap::new));
            for (AiNewsSourceEndpointEntity row : rows) {
                if (providerAttempted >= providerBudget) break;
                NewsSourceEndpointDescriptor descriptor = byKey.get(row.getEndpointKey());
                if (descriptor == null) continue;
                if (!ledger.isDue(row, now) || !ledger.claimDue(row, now, claimLease)) continue;
                attempted++;
                providerAttempted++;
                PollOutcome outcome = pollOne(provider, descriptor, row, triggerType);
                newItems += outcome.newItems();
                newVersions += outcome.newVersions();
                switch (outcome.status()) {
                    case "success", "not_modified" -> succeeded++;
                    case "degraded" -> degraded++;
                    default -> failed++;
                }
                if (!outcome.error().isBlank()) errors.add(outcome.error());
            }
        }
        return new CycleSummary(true, configured, attempted, succeeded, degraded, failed,
                newItems, newVersions, stale, List.copyOf(errors));
    }

    private static int fairBudget(int providerIndex, int providerCount, int totalBudget) {
        if (providerCount <= 0 || totalBudget <= 0) return 0;
        int base = totalBudget / providerCount;
        int remainder = totalBudget % providerCount;
        return base + (providerIndex < remainder ? 1 : 0);
    }

    public List<NewsSourceResult> recentCandidates(Instant since, int limit,
                                                    boolean refreshIfEmpty) {
        int lookbackDays = Math.min(Math.max(properties.getCandidateLookbackDays(), 1), 90);
        Instant earliest = Instant.now().minus(Duration.ofDays(lookbackDays));
        Instant threshold = since == null || since.isBefore(earliest) ? earliest : since;
        List<NewsSourceResult> rows = ledger.recentResults(threshold, limit);
        if (!rows.isEmpty() || !properties.isEnabled() || !refreshIfEmpty
                || !properties.isOnDemandRefreshIfEmpty()) return rows;
        runDueCycle("on_demand");
        return ledger.recentResults(threshold, limit);
    }

    private PollOutcome pollOne(ScheduledNewsSourceProvider provider,
                                NewsSourceEndpointDescriptor descriptor,
                                AiNewsSourceEndpointEntity endpoint,
                                String triggerType) {
        AiNewsIngestionRunEntity run = null;
        try {
            run = ledger.startRun(endpoint, descriptor, triggerType);
            NewsSourcePollBatch batch;
            try {
                batch = provider.poll(descriptor, ledger.validators(endpoint));
            } catch (Exception error) {
                batch = NewsSourcePollBatch.failed(descriptor, Instant.now(), null,
                        "PROVIDER_ERROR", safe(error.getMessage()));
            }
            AiNewsIngestionLedgerService.Completion completed =
                    ledger.completeRun(run, endpoint, batch);
            metrics.recordRun(batch, triggerType, completed.newItemCount(),
                    completed.newVersionCount(), completed.unchangedItemCount());
            String error = batch.errorMessage().isBlank() ? ""
                    : descriptor.endpointKey() + ": " + batch.errorMessage();
            return new PollOutcome(completed.status(), completed.newItemCount(),
                    completed.newVersionCount(), error);
        } catch (Exception error) {
            try {
                ledger.markPersistenceFailure(run, endpoint, error);
            } catch (Exception markError) {
                log.error("Could not close failed AI-news ingestion run for endpoint {}: {}",
                        descriptor.endpointKey(), markError.getMessage());
            }
            metrics.recordTerminalFailure(descriptor, triggerType, "persistence_failed");
            String message = descriptor.endpointKey() + ": " + safe(error.getMessage());
            log.warn("AI-news endpoint poll failed: {}", message);
            return new PollOutcome("persistence_failed", 0, 0, message);
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown error";
        String text = value.trim();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private record PollOutcome(String status, int newItems, int newVersions, String error) {
    }

    public record CycleSummary(boolean enabled,
                               int configuredEndpoints,
                               int attemptedEndpoints,
                               int succeededEndpoints,
                               int degradedEndpoints,
                               int failedEndpoints,
                               int newItems,
                               int newVersions,
                               int abandonedRuns,
                               List<String> errors) {
        public CycleSummary {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        static CycleSummary disabled() {
            return new CycleSummary(false, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }
    }
}

package vip.newsclaw.news.service;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Cluster-singleton scan that polls only endpoints whose persisted cursor is due. */
@Service
@Slf4j
public class AiNewsIngestionScheduler {

    private final AiNewsStructuredIngestionService ingestionService;
    private final AiNewsIngestionProperties properties;

    public AiNewsIngestionScheduler(AiNewsStructuredIngestionService ingestionService,
                                    AiNewsIngestionProperties properties) {
        this.ingestionService = ingestionService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${newsclaw.ai-news.ingestion.scan-interval-ms:60000}",
            initialDelayString = "${newsclaw.ai-news.ingestion.initial-delay-ms:15000}")
    @SchedulerLock(name = "ai-news-structured-ingestion",
            // A cycle may poll many endpoints (each with its own HTTP timeout).
            // The lease must outlive the worst bounded cycle or a second node can
            // overlap it and violate endpoint/cursor fencing.
            lockAtMostFor = "PT2H", lockAtLeastFor = "PT5S")
    public void scheduledPoll() {
        if (!properties.isEnabled()) return;
        AiNewsStructuredIngestionService.CycleSummary summary = runCycle();
        if (summary.attemptedEndpoints() > 0 || summary.abandonedRuns() > 0) {
            log.info("[AiNewsIngestion] endpoints={}/{} success={} degraded={} failed={} "
                            + "newItems={} newVersions={} abandoned={}",
                    summary.attemptedEndpoints(), summary.configuredEndpoints(),
                    summary.succeededEndpoints(), summary.degradedEndpoints(),
                    summary.failedEndpoints(), summary.newItems(), summary.newVersions(),
                    summary.abandonedRuns());
        }
        if (!summary.errors().isEmpty()) {
            log.warn("[AiNewsIngestion] cycle completed with {} issue(s): {}",
                    summary.errors().size(), summary.errors());
        }
    }

    public AiNewsStructuredIngestionService.CycleSummary runCycle() {
        return ingestionService.runDueCycle("scheduled");
    }
}

package vip.newsclaw.news.service;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** Cluster-singleton trigger for the feature-flagged candidate pipeline. */
@Service
@Slf4j
public class AiNewsCandidatePipelineScheduler {

    private final AiNewsScanOrchestrator orchestrator;
    private final AiNewsCandidateCaptureWorker captureWorker;

    @Autowired(required = false)
    private AiNewsRetentionService retentionService;

    public AiNewsCandidatePipelineScheduler(AiNewsScanOrchestrator orchestrator,
                                            AiNewsCandidateCaptureWorker captureWorker) {
        this.orchestrator = orchestrator;
        this.captureWorker = captureWorker;
    }

    @Scheduled(
            fixedDelayString = "${newsclaw.ai-news.candidate-pipeline.scan-interval-ms:900000}",
            initialDelayString = "${newsclaw.ai-news.candidate-pipeline.initial-delay-ms:60000}")
    @SchedulerLock(name = "ai-news-candidate-pipeline", lockAtMostFor = "PT2H",
            lockAtLeastFor = "PT5S")
    public void scheduledScan() {
        if (!orchestrator.enabled()) return;
        try {
            int recovered = orchestrator.recoverStaleRuns();
            if (recovered > 0) {
                log.info("[AiNewsCandidatePipeline] reconciled {} stale scan(s)", recovered);
            }
            var summary = orchestrator.runDefault("scheduled");
            log.info("[AiNewsCandidatePipeline] scanId={} raw={} candidates={} selected={} capture={}/{}",
                    summary.run().getId(), summary.run().getRawResultCount(),
                    summary.run().getUniqueCandidateCount(), summary.run().getSelectedCandidateCount(),
                    summary.run().getCaptureSuccessCount(), summary.run().getCaptureFailureCount());
        } catch (RuntimeException error) {
            log.warn("[AiNewsCandidatePipeline] scheduled scan failed: {}", error.getMessage());
        }
    }

    @Scheduled(
            fixedDelayString = "${newsclaw.ai-news.candidate-pipeline.capture-interval-ms:60000}",
            initialDelayString = "${newsclaw.ai-news.candidate-pipeline.initial-delay-ms:60000}")
    @SchedulerLock(name = "ai-news-candidate-capture", lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT1S")
    public void scheduledCapture() {
        if (!orchestrator.enabled()) return;
        try {
            var summary = captureWorker.runPending();
            if (summary.claimed() > 0 || summary.recoveredStale() > 0) {
                log.info("[AiNewsCandidatePipeline] capture queue recovered={} claimed={} "
                                + "succeeded={} failed={} retryable={}",
                        summary.recoveredStale(), summary.claimed(), summary.succeeded(),
                        summary.failed(), summary.retryable());
            }
        } catch (RuntimeException error) {
            log.warn("[AiNewsCandidatePipeline] capture queue failed: {}", error.getMessage());
        }
    }

    @Scheduled(cron = "${newsclaw.ai-news.candidate-pipeline.retention-cron:0 30 3 * * ?}")
    @SchedulerLock(name = "ai-news-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1S")
    public void scheduledRetention() {
        if (retentionService == null) return;
        AiNewsRetentionService.RetentionSummary summary = retentionService.purgeExpired();
        if (summary.scanRuns() > 0 || summary.sourceCaptures() > 0) {
            log.info("[AiNewsRetention] scans={} candidates={} observations={} snapshots={} captures={}",
                    summary.scanRuns(), summary.candidates(), summary.observations(),
                    summary.discoverySnapshots(), summary.sourceCaptures());
        }
    }
}

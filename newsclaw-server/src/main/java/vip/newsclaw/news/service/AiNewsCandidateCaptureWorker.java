package vip.newsclaw.news.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsCandidateEntity;

import java.time.Duration;
import java.util.List;

/** Deterministic, bounded candidate-to-capture worker; no model or Agent state is involved. */
@Service
@Slf4j
public class AiNewsCandidateCaptureWorker {

    private final AiNewsCandidatePipelineService pipelineService;
    private final AiNewsSourceCaptureService captureService;
    private final AiNewsCandidatePipelineProperties properties;

    public AiNewsCandidateCaptureWorker(AiNewsCandidatePipelineService pipelineService,
                                        AiNewsSourceCaptureService captureService,
                                        AiNewsCandidatePipelineProperties properties) {
        this.pipelineService = pipelineService;
        this.captureService = captureService;
        this.properties = properties;
    }

    public synchronized WorkerSummary run(Long scanRunId, int requestedLimit) {
        if (!properties.isCaptureEnabled()) return WorkerSummary.disabled();
        return runQueue(scanRunId, null, requestedLimit);
    }

    private WorkerSummary runQueue(Long scanRunId, Long workspaceId, int requestedLimit) {
        int recovered = pipelineService.recoverStaleCaptures(workspaceId, scanRunId,
                Duration.ofMinutes(Math.min(Math.max(1, properties.getStaleCaptureMinutes()), 1440)));
        int limit = Math.min(Math.max(1, requestedLimit), 100);
        List<AiNewsCandidateEntity> queue = workspaceId == null
                ? pipelineService.captureQueue(scanRunId, Math.min(100, limit * 5))
                : pipelineService.captureQueueForWorkspace(workspaceId, Math.min(100, limit * 5));
        int claimed = 0;
        int succeeded = 0;
        int failed = 0;
        int retryable = 0;
        for (AiNewsCandidateEntity candidate : queue) {
            if (claimed >= limit) break;
            AiNewsCandidatePipelineService.CaptureLease lease =
                    pipelineService.claimCaptureLease(candidate.getId());
            if (lease == null) continue;
            claimed++;
            try {
                AiNewsSourceCaptureService.CaptureSummary capture = captureService.capture(
                        candidate.getWorkspaceId(), candidate.getCanonicalUrl());
                String admissionFailure = pipelineService.captureWindowFailure(
                        candidate.getId(), capture.sourcePublishedAtUtc());
                if (admissionFailure != null) {
                    boolean recorded = pipelineService.captureFailed(candidate.getId(),
                            admissionFailure, false, properties.getMaxCaptureAttempts(),
                            Duration.ZERO, lease.attempt());
                    if (recorded) failed++;
                    continue;
                }
                boolean recorded = pipelineService.captureSucceeded(candidate.getId(),
                        Long.valueOf(capture.captureId()), lease.attempt());
                if (recorded) succeeded++;
                else log.debug("Ignoring fenced completion for candidateId={}, attempt={}",
                        candidate.getId(), lease.attempt());
            } catch (Exception error) {
                boolean canRetry = retryable(error);
                boolean recorded = pipelineService.captureFailed(candidate.getId(), failure(error), canRetry,
                        properties.getMaxCaptureAttempts(), Duration.ofMinutes(
                                Math.min(Math.max(1, properties.getCaptureRetryMinutes()), 1440)),
                        lease.attempt());
                if (recorded) {
                    if (canRetry) retryable++;
                    else failed++;
                } else {
                    log.debug("Ignoring fenced failure for candidateId={}, attempt={}",
                            candidate.getId(), lease.attempt());
                }
                log.info("AI-news candidate capture did not produce usable content: candidateId={}, retryable={}",
                        candidate.getId(), canRetry);
            }
        }
        return new WorkerSummary(true, recovered, queue.size(), claimed, succeeded, failed, retryable);
    }

    public WorkerSummary runPending() {
        // This is an internal cluster-singleton worker, not a user-facing
        // endpoint. Drain all workspace queues so a manually-triggered run in
        // a non-default workspace cannot remain CAPTURE_PENDING forever.
        // Candidate/run/workspace fences live in the mapper and capture is
        // invoked with the candidate's persisted workspace id.
        if (!properties.isCaptureEnabled()) return WorkerSummary.disabled();
        return runQueue(null, null, properties.getMaxCapturesPerScan());
    }

    private static boolean retryable(Exception error) {
        if (error instanceof NewsClawException business) {
            return business.getCode() == 429 || business.getCode() == 502
                    || business.getCode() == 503 || business.getCode() == 504;
        }
        return true;
    }

    private static String failure(Exception error) {
        String value = error == null ? "unknown capture failure" : error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        value = value.replaceAll("[\r\n]+", " ").trim();
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    public record WorkerSummary(boolean enabled,
                                int recoveredStale,
                                int queued,
                                int claimed,
                                int succeeded,
                                int failed,
                                int retryable) {
        static WorkerSummary disabled() {
            return new WorkerSummary(false, 0, 0, 0, 0, 0, 0);
        }
    }
}

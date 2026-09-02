package vip.newsclaw.workflow.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.newsclaw.workflow.model.WorkflowRunEntity;
import vip.newsclaw.workflow.repository.WorkflowRunMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Reconciles workflow rows stranded in {@code running} after a process crash.
 *
 * <p>Workflow steps are not remotely resumable in v0, so silently leaving a
 * row running is worse than an explicit terminal failure. The age is
 * deliberately conservative and configurable; a conditional update lets a
 * genuinely live worker win if it races the sweep.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowRunRecoveryService {

    private final WorkflowRunMapper runMapper;

    @Value("${newsclaw.workflow.recovery.stale-after-minutes:1440}")
    private long staleAfterMinutes;

    /** Run periodically on the shared Spring scheduler; each tick is bounded. */
    @Scheduled(
            fixedDelayString = "${newsclaw.workflow.recovery.interval-ms:300000}",
            initialDelayString = "${newsclaw.workflow.recovery.initial-delay-ms:120000}")
    public void recoverStaleRuns() {
        long ageMinutes = Math.max(1L, staleAfterMinutes);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(ageMinutes);
        List<WorkflowRunEntity> stale;
        try {
            stale = runMapper.selectStaleRunning(cutoff);
        } catch (Exception e) {
            log.warn("[WorkflowRecovery] stale-run scan failed: {}", e.getMessage());
            return;
        }
        if (stale == null || stale.isEmpty()) return;

        int recovered = 0;
        LocalDateTime now = LocalDateTime.now();
        for (WorkflowRunEntity run : stale) {
            if (run == null || run.getId() == null) continue;
            try {
                if (runMapper.failStaleRunning(run.getId(),
                        "workflow run recovered after stale lease (process restart or worker crash)", now) == 1) {
                    recovered++;
                }
            } catch (Exception e) {
                log.debug("[WorkflowRecovery] failed to settle run {}: {}", run.getId(), e.getMessage());
            }
        }
        if (recovered > 0) {
            log.warn("[WorkflowRecovery] marked {} stale running workflow run(s) as failed (age >= {} min)",
                    recovered, ageMinutes);
        }
    }
}

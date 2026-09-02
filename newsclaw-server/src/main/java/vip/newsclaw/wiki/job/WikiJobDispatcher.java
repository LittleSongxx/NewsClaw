package vip.newsclaw.wiki.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import vip.newsclaw.wiki.job.event.WikiJobCreatedEvent;
import vip.newsclaw.wiki.repository.WikiProcessingJobMapper;
import vip.newsclaw.wiki.job.model.WikiProcessingJobEntity;
import vip.newsclaw.wiki.job.template.WikiProcessingTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * RFC-031: Listens for WikiJobCreatedEvent and dispatches jobs
 * to the appropriate processing template.
 */
@Slf4j
@Component
public class WikiJobDispatcher {

    private final Map<String, WikiProcessingTemplate> templateMap;
    private final WikiProcessingJobMapper jobMapper;

    private static final ExecutorService DISPATCH_EXECUTOR =
        Executors.newVirtualThreadPerTaskExecutor();

    public WikiJobDispatcher(List<WikiProcessingTemplate> templates,
                              WikiProcessingJobMapper jobMapper) {
        this.jobMapper = jobMapper;
        this.templateMap = templates.stream().collect(
            Collectors.toMap(t -> t.getClass().getSimpleName(), t -> t));
        log.info("[WikiDispatcher] Registered templates: {}", templateMap.keySet());
    }

    @EventListener(WikiJobCreatedEvent.class)
    public void onJobCreated(WikiJobCreatedEvent event) {
        DISPATCH_EXECUTOR.submit(() -> dispatch(event.jobId()));
    }

    public void dispatch(Long jobId) {
        WikiProcessingJobEntity job = jobMapper.selectById(jobId);
        if (job == null) return;

        if (!"queued".equals(job.getStatus())) return;
        try {
            WikiJobStage stage = WikiJobStage.valueOf(job.getStage().toUpperCase());
            if (stage.isTerminal()) return;
        } catch (IllegalArgumentException e) {
            log.warn("[WikiDispatch] Unknown stage '{}' for job {}", job.getStage(), jobId);
            return;
        }

        WikiProcessingTemplate template = resolveTemplate(job.getJobType());
        if (template == null) {
            log.error("[WikiDispatch] No template for job type: {}", job.getJobType());
            return;
        }
        if (jobMapper.claimQueued(jobId) != 1) return;
        job = jobMapper.selectById(jobId);
        if (job == null) return;
        template.execute(job);
    }

    @Scheduled(fixedDelayString = "${newsclaw.wiki.job-dispatch-interval-ms:30000}")
    @SchedulerLock(name = "wiki-processing-job-dispatch", lockAtMostFor = "PT2M")
    public void dispatchQueued() {
        jobMapper.listAllQueued(100).forEach(job ->
                DISPATCH_EXECUTOR.submit(() -> dispatch(job.getId())));
    }

    private WikiProcessingTemplate resolveTemplate(String jobType) {
        return switch (jobType) {
            case "heavy_ingest"  -> templateMap.get("HeavyIngestTemplate");
            case "light_enrich"  -> templateMap.get("LightEnrichTemplate");
            case "local_repair"  -> templateMap.get("LocalRepairTemplate");
            default              -> null;
        };
    }
}

package vip.newsclaw.wiki;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import vip.newsclaw.wiki.job.WikiProcessingJobService;
import vip.newsclaw.wiki.service.WikiRawMaterialService;
import vip.newsclaw.wiki.job.event.WikiJobCreatedEvent;
import vip.newsclaw.wiki.repository.WikiProcessingJobMapper;
import vip.newsclaw.wiki.repository.WikiPipelineRunMapper;
import vip.newsclaw.wiki.repository.WikiPipelineStepRunMapper;
import vip.newsclaw.wiki.repository.WikiTransformationRunMapper;
import vip.newsclaw.wiki.model.WikiPipelineRunEntity;
import vip.newsclaw.wiki.model.WikiPipelineStepRunEntity;
import vip.newsclaw.wiki.model.WikiTransformationRunEntity;
import org.springframework.context.ApplicationEventPublisher;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDateTime;

/**
 * Wiki module auto-configuration
 *
 * @author NewsClaw Team
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WikiProperties.class)
@RequiredArgsConstructor
public class WikiAutoConfiguration {

    private final WikiProcessingJobService wikiProcessingJobService;
    private final WikiRawMaterialService wikiRawMaterialService;
    private final WikiProcessingJobMapper processingJobMapper;
    private final WikiPipelineRunMapper pipelineRunMapper;
    private final WikiPipelineStepRunMapper pipelineStepRunMapper;
    private final WikiTransformationRunMapper transformationRunMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Recover stuck wiki state on startup:
     * 1. Job table: routing/*_running → queued (RFC-030)
     * 2. Raw material table: processing → pending (avoids forever-spinning progress bars)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverWikiJobs(ApplicationReadyEvent event) {
        wikiProcessingJobService.recoverOnStartup();
        int recovered = wikiRawMaterialService.recoverStuckRawMaterialsOnStartup();
        if (recovered > 0) {
            log.info("[Wiki] Recovered {} stuck raw materials on startup", recovered);
        }
        processingJobMapper.listAllQueued(1_000)
                .forEach(job -> eventPublisher.publishEvent(new WikiJobCreatedEvent(job.getId())));

        LocalDateTime now = LocalDateTime.now();
        int stepRuns = pipelineStepRunMapper.update(null,
                new LambdaUpdateWrapper<WikiPipelineStepRunEntity>()
                        .eq(WikiPipelineStepRunEntity::getStatus, "running")
                        .set(WikiPipelineStepRunEntity::getStatus, "failed")
                        .set(WikiPipelineStepRunEntity::getErrorMessage, "Server restarted during execution")
                        .set(WikiPipelineStepRunEntity::getFinishedAt, now));
        int pipelineRuns = pipelineRunMapper.update(null,
                new LambdaUpdateWrapper<WikiPipelineRunEntity>()
                        .eq(WikiPipelineRunEntity::getStatus, "running")
                        .set(WikiPipelineRunEntity::getStatus, "failed")
                        .set(WikiPipelineRunEntity::getErrorMessage, "Server restarted during execution")
                        .set(WikiPipelineRunEntity::getFinishedAt, now));
        int transformations = transformationRunMapper.update(null,
                new LambdaUpdateWrapper<WikiTransformationRunEntity>()
                        .in(WikiTransformationRunEntity::getStatus, "pending", "running")
                        .set(WikiTransformationRunEntity::getStatus, "failed")
                        .set(WikiTransformationRunEntity::getError, "Server restarted during execution")
                        .set(WikiTransformationRunEntity::getCompletedAt, now));
        if (stepRuns + pipelineRuns + transformations > 0) {
            log.warn("[Wiki] Marked interrupted async runs failed: pipelineRuns={}, stepRuns={}, transformations={}",
                    pipelineRuns, stepRuns, transformations);
        }
    }
}

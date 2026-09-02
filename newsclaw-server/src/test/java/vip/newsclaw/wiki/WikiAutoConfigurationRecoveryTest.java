package vip.newsclaw.wiki;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import vip.newsclaw.wiki.job.WikiProcessingJobService;
import vip.newsclaw.wiki.job.event.WikiJobCreatedEvent;
import vip.newsclaw.wiki.job.model.WikiProcessingJobEntity;
import vip.newsclaw.wiki.repository.*;
import vip.newsclaw.wiki.service.WikiRawMaterialService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WikiAutoConfigurationRecoveryTest {

    @Test
    void startupRedispatchesRecoveredQueuedJobs() {
        WikiProcessingJobService jobs = mock(WikiProcessingJobService.class);
        WikiRawMaterialService raws = mock(WikiRawMaterialService.class);
        WikiProcessingJobMapper jobMapper = mock(WikiProcessingJobMapper.class);
        WikiProcessingJobEntity queued = new WikiProcessingJobEntity();
        queued.setId(7L);
        when(jobMapper.listAllQueued(1_000)).thenReturn(List.of(queued));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        WikiPipelineRunMapper pipelineRuns = mock(WikiPipelineRunMapper.class);
        WikiPipelineStepRunMapper stepRuns = mock(WikiPipelineStepRunMapper.class);
        WikiTransformationRunMapper transformations = mock(WikiTransformationRunMapper.class);
        WikiAutoConfiguration config = new WikiAutoConfiguration(
                jobs, raws, jobMapper,
                pipelineRuns, stepRuns, transformations, events);

        config.recoverWikiJobs(mock(ApplicationReadyEvent.class));

        verify(jobs).recoverOnStartup();
        verify(raws).recoverStuckRawMaterialsOnStartup();
        verify(events).publishEvent(new WikiJobCreatedEvent(7L));
        verify(pipelineRuns).update(any(), any());
        verify(stepRuns).update(any(), any());
        verify(transformations).update(any(), any());
    }
}

package vip.newsclaw.wiki.job.template;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.newsclaw.wiki.WikiProperties;
import vip.newsclaw.wiki.job.*;
import vip.newsclaw.wiki.job.event.WikiJobCreatedEvent;
import vip.newsclaw.wiki.job.model.WikiProcessingJobEntity;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WikiProcessingTemplateRetryDispatchTest {

    @Test
    void retryableFailurePublishesARealRedispatchEvent() {
        WikiModelRoutingService routing = mock(WikiModelRoutingService.class);
        WikiProcessingJobService jobs = mock(WikiProcessingJobService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        WikiProcessingJobEntity job = new WikiProcessingJobEntity();
        job.setId(9L);
        when(jobs.transition(eq(9L), any())).thenReturn(job);
        when(routing.selectModelId(job, WikiJobStep.CREATE_PAGE)).thenReturn(1L);
        WikiProcessingTemplate template = new WikiProcessingTemplate(
                routing, jobs, events, new WikiProperties()) {
            @Override protected WikiJobStep routingStep() { return WikiJobStep.CREATE_PAGE; }
            @Override protected WikiJobStage mainStage() { return WikiJobStage.PHASE_A_RUNNING; }
            @Override protected void doProcess(WikiProcessingJobEntity ignored, Long modelId) {
                throw new RuntimeException("transient");
            }
        };

        template.execute(job);

        verify(jobs).recordSoftError(9L, "UNKNOWN", "transient");
        verify(events).publishEvent(new WikiJobCreatedEvent(9L));
    }
}

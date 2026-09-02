package vip.newsclaw.wiki.job;

import org.junit.jupiter.api.Test;
import vip.newsclaw.wiki.job.model.WikiProcessingJobEntity;
import vip.newsclaw.wiki.job.template.HeavyIngestTemplate;
import vip.newsclaw.wiki.repository.WikiProcessingJobMapper;

import java.util.List;

import static org.mockito.Mockito.*;

class WikiJobDispatcherClaimTest {

    @Test
    void competingDispatcherThatLosesQueuedClaimDoesNotExecuteTemplate() {
        WikiProcessingJobMapper mapper = mock(WikiProcessingJobMapper.class);
        WikiProcessingJobEntity job = new WikiProcessingJobEntity();
        job.setId(7L);
        job.setStatus("queued");
        job.setStage("queued");
        job.setJobType("heavy_ingest");
        when(mapper.selectById(7L)).thenReturn(job);
        when(mapper.claimQueued(7L)).thenReturn(0);
        HeavyIngestTemplate template = mock(HeavyIngestTemplate.class);

        new WikiJobDispatcher(List.of(template), mapper).dispatch(7L);

        verify(template, never()).execute(any());
    }
}

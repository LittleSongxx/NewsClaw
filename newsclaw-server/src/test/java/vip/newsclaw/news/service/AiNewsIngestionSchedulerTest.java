package vip.newsclaw.news.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiNewsIngestionSchedulerTest {

    @Test
    void scheduledEntryPointIsClusterLockedAndConfigurationDriven() throws Exception {
        Method method = AiNewsIngestionScheduler.class.getMethod("scheduledPoll");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertNotNull(scheduled);
        assertEquals("${newsclaw.ai-news.ingestion.scan-interval-ms:60000}",
                scheduled.fixedDelayString());
        assertNotNull(lock);
        assertEquals("ai-news-structured-ingestion", lock.name());
        // A cycle may poll up to the configured fair budget of endpoints;
        // keep the cluster lease longer than the stale-run recovery window so
        // a slow cycle cannot overlap with a second scheduler owner.
        assertEquals("PT2H", lock.lockAtMostFor());
    }

    @Test
    void disabledSchedulerDoesNotTouchTheOrchestrator() {
        AiNewsStructuredIngestionService ingestion = mock(AiNewsStructuredIngestionService.class);
        AiNewsIngestionProperties properties = new AiNewsIngestionProperties();
        properties.setEnabled(false);

        new AiNewsIngestionScheduler(ingestion, properties).scheduledPoll();

        verifyNoInteractions(ingestion);
    }

    @Test
    void manualCycleUsesScheduledTriggerContract() {
        AiNewsStructuredIngestionService ingestion = mock(AiNewsStructuredIngestionService.class);
        AiNewsIngestionProperties properties = new AiNewsIngestionProperties();
        var expected = new AiNewsStructuredIngestionService.CycleSummary(
                true, 1, 1, 1, 0, 0, 0, 0, 0, List.of());
        when(ingestion.runDueCycle("scheduled")).thenReturn(expected);

        assertEquals(expected, new AiNewsIngestionScheduler(ingestion, properties).runCycle());
        verify(ingestion).runDueCycle("scheduled");
    }
}

package vip.newsclaw.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.trigger.dispatch.DispatchResult;
import vip.newsclaw.trigger.dispatch.TriggerDispatcher;
import vip.newsclaw.trigger.model.TriggerEntity;
import vip.newsclaw.trigger.repository.TriggerMapper;
import vip.newsclaw.trigger.scheduler.TriggerScheduler;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TriggerSchedulerCoordinationTest {

    @Test
    void rechecksEnabledStateAfterAcquiringDistributedLock() {
        TriggerMapper mapper = mock(TriggerMapper.class);
        TriggerDispatcher dispatcher = mock(TriggerDispatcher.class);
        LockProvider provider = mock(LockProvider.class);
        SimpleLock lock = mock(SimpleLock.class);
        when(mapper.selectById(7L)).thenReturn(trigger(true), trigger(false));
        when(provider.lock(any())).thenReturn(Optional.of(lock));
        TriggerScheduler scheduler = new TriggerScheduler(mapper, dispatcher, provider,
                new ObjectMapper());

        scheduler.fireForTest(7L, 1L);

        verifyNoInteractions(dispatcher);
        verify(lock).unlock();
    }

    @Test
    void unlockFailureDoesNotEscapeTheRecurringTask() {
        TriggerMapper mapper = mock(TriggerMapper.class);
        TriggerDispatcher dispatcher = mock(TriggerDispatcher.class);
        LockProvider provider = mock(LockProvider.class);
        SimpleLock lock = mock(SimpleLock.class);
        when(mapper.selectById(7L)).thenReturn(trigger(true));
        when(provider.lock(any())).thenReturn(Optional.of(lock));
        when(dispatcher.dispatch(any(), anyMap())).thenReturn(DispatchResult.fired(9L));
        doThrow(new IllegalStateException("lease already expired")).when(lock).unlock();
        TriggerScheduler scheduler = new TriggerScheduler(mapper, dispatcher, provider,
                new ObjectMapper());

        assertDoesNotThrow(() -> scheduler.fireForTest(7L, 1L));
        verify(mapper).recordDispatchOutcome(any(), anyInt(), isNull(), any());
    }

    @Test
    void invalidCronDoesNotPreventLaterRowsFromRegistering() {
        TriggerMapper mapper = mock(TriggerMapper.class);
        TriggerScheduler scheduler = new TriggerScheduler(mapper, mock(TriggerDispatcher.class),
                mock(LockProvider.class), new ObjectMapper());
        TriggerEntity invalid = trigger(true);
        invalid.setPatternType("cron");
        invalid.setPatternJson("{\"cron\":\"not-a-cron\"}");
        TriggerEntity valid = trigger(true);
        valid.setId(8L);
        valid.setPatternType("cron");
        valid.setPatternJson("{\"cron\":\"0 0 * * * *\"}");
        when(mapper.selectList(any())).thenReturn(List.of(invalid, valid));

        ReflectionTestUtils.invokeMethod(scheduler, "initScheduler");
        try {
            scheduler.syncFromDatabase();
            assertTrue(scheduler.isRegistered(8L));
        } finally {
            ReflectionTestUtils.invokeMethod(scheduler, "shutdownScheduler");
        }
    }

    private static TriggerEntity trigger(boolean enabled) {
        TriggerEntity trigger = new TriggerEntity();
        trigger.setId(7L);
        trigger.setEnabled(enabled);
        trigger.setDeleted(0);
        trigger.setPatternVersion(1L);
        trigger.setMaxFires(0L);
        return trigger;
    }
}

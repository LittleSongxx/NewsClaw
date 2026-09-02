package vip.newsclaw.cron.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.channel.repository.ChannelMapper;
import vip.newsclaw.cron.model.CronJobDTO;
import vip.newsclaw.cron.model.CronJobEntity;
import vip.newsclaw.cron.repository.CronJobMapper;
import vip.newsclaw.wiki.repository.WikiKnowledgeBaseMapper;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CronJobSchedulingGuardTest {

    @Test
    void createRejectsAgentFromAnotherWorkspace() {
        CronJobMapper mapper = mock(CronJobMapper.class);
        AgentMapper agents = mock(AgentMapper.class);
        AgentEntity foreign = new AgentEntity();
        foreign.setId(99L);
        foreign.setWorkspaceId(2L);
        when(agents.selectById(99L)).thenReturn(foreign);
        CronJobService service = new CronJobService(mapper, agents,
                mock(ChannelMapper.class), mock(WikiKnowledgeBaseMapper.class),
                new ObjectMapper(), mock(LockProvider.class), mock(CronJobRunner.class));
        CronJobDTO dto = new CronJobDTO();
        dto.setName("foreign-agent");
        dto.setCronExpression("0 8 * * *");
        dto.setAgentId(99L);
        dto.setTaskType("text");
        dto.setTriggerMessage("run");
        dto.setEnabled(false);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> service.create(dto, 1L));
        } finally {
            service.destroy();
        }
    }

    @Test
    void scheduledRunRechecksEnabledStateAfterWaitingForPermit() {
        CronJobMapper mapper = mock(CronJobMapper.class);
        CronJobRunner runner = mock(CronJobRunner.class);
        CronJobService service = new CronJobService(mapper, mock(AgentMapper.class),
                mock(ChannelMapper.class), mock(WikiKnowledgeBaseMapper.class),
                new ObjectMapper(), mock(net.javacrumbs.shedlock.core.LockProvider.class), runner);
        CronJobEntity active = job(true);
        CronJobEntity disabled = job(false);
        when(mapper.selectById(7L)).thenReturn(active, disabled);

        try {
            ReflectionTestUtils.invokeMethod(service, "runWithBackpressure", active, "scheduled");
            verifyNoInteractions(runner);
        } finally {
            service.destroy();
        }
    }

    @Test
    void staleScheduleDoesNotFireAfterCronExpressionChanges() {
        CronJobMapper mapper = mock(CronJobMapper.class);
        CronJobRunner runner = mock(CronJobRunner.class);
        CronJobService service = new CronJobService(mapper, mock(AgentMapper.class),
                mock(ChannelMapper.class), mock(WikiKnowledgeBaseMapper.class),
                new ObjectMapper(), mock(net.javacrumbs.shedlock.core.LockProvider.class), runner);
        CronJobEntity snapshot = job(true);
        CronJobEntity changed = job(true);
        changed.setCronExpression("30 9 * * *");
        when(mapper.selectById(7L)).thenReturn(changed);

        try {
            ReflectionTestUtils.invokeMethod(service, "runWithBackpressure", snapshot, "scheduled");
            verifyNoInteractions(runner);
        } finally {
            service.destroy();
        }
    }

    @Test
    void rejectedExecutorSubmissionReleasesDistributedLock() {
        CronJobMapper mapper = mock(CronJobMapper.class);
        CronJobRunner runner = mock(CronJobRunner.class);
        LockProvider provider = mock(LockProvider.class);
        SimpleLock lock = mock(SimpleLock.class);
        when(provider.lock(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(lock));
        CronJobService service = new CronJobService(mapper, mock(AgentMapper.class),
                mock(ChannelMapper.class), mock(WikiKnowledgeBaseMapper.class),
                new ObjectMapper(), provider, runner);
        service.destroy();

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.tickWithDistributedLock(job(true)));
        verify(lock).unlock();
        verifyNoInteractions(runner);
    }

    private static CronJobEntity job(boolean enabled) {
        CronJobEntity job = new CronJobEntity();
        job.setId(7L);
        job.setEnabled(enabled);
        job.setDeleted(0);
        job.setCronExpression("0 8 * * *");
        job.setTimezone("Asia/Shanghai");
        return job;
    }
}

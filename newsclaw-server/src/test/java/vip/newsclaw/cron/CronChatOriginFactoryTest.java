package vip.newsclaw.cron;

import org.junit.jupiter.api.Test;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.cron.model.CronJobEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CronChatOriginFactoryTest {

    @Test
    void explicitMessageIdIsCarriedByTheCronOrigin() {
        AgentMapper agents = mock(AgentMapper.class);
        AgentEntity agent = new AgentEntity();
        agent.setWorkspaceId(30L);
        CronJobEntity job = new CronJobEntity();
        job.setAgentId(20L);
        when(agents.selectById(20L)).thenReturn(agent);

        ChatOrigin origin = new CronChatOriginFactory(agents).from(job, "tasks_30", 99L);

        assertEquals(99L, origin.originMessageId());
    }
}

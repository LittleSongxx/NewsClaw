package vip.newsclaw.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.channel.feishu.FeishuClientFactory;
import vip.newsclaw.channel.model.ChannelEntity;
import vip.newsclaw.channel.repository.ChannelMapper;
import vip.newsclaw.channel.tool.ChannelToolService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelServiceWorkspaceBindingTest {

    @Test
    void createRejectsAgentFromAnotherWorkspace() {
        ChannelMapper channels = mock(ChannelMapper.class);
        AgentMapper agents = mock(AgentMapper.class);
        AgentEntity foreign = new AgentEntity();
        foreign.setId(9L);
        foreign.setWorkspaceId(2L);
        when(agents.selectById(9L)).thenReturn(foreign);
        ChannelService service = new ChannelService(channels, agents, new ObjectMapper(),
                mock(ObjectProvider.class), mock(ObjectProvider.class));

        ChannelEntity row = new ChannelEntity();
        row.setName("web");
        row.setChannelType("webchat");
        row.setWorkspaceId(1L);
        row.setAgentId(9L);

        assertThrows(RuntimeException.class, () -> service.createChannel(row));
    }
}

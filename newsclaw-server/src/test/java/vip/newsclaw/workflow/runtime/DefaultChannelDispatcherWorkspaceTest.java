package vip.newsclaw.workflow.runtime;

import org.junit.jupiter.api.Test;
import vip.newsclaw.channel.ChannelAdapter;
import vip.newsclaw.channel.ChannelManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultChannelDispatcherWorkspaceTest {

    @Test
    void dispatchUsesWorkspaceScopedAdapterLookup() {
        ChannelManager manager = mock(ChannelManager.class);
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(manager.getAdapterByTypeAndWorkspace("feishu", 9L)).thenReturn(Optional.of(adapter));
        when(adapter.isRunning()).thenReturn(true);
        when(adapter.supportsProactiveSend()).thenReturn(true);

        var result = new DefaultChannelDispatcher(manager)
                .dispatch(9L, "feishu", "chat", "hello");

        assertTrue(result.success());
        verify(manager).getAdapterByTypeAndWorkspace("feishu", 9L);
        verify(adapter).proactiveSend("chat", "hello");
    }
}

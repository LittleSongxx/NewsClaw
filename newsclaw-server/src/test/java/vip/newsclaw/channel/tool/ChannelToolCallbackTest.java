package vip.newsclaw.channel.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import vip.newsclaw.agent.context.ChatOrigin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Pin the {@code renamed(actualName)} contract — {@link ChannelToolService}
 * relies on it to apply the {@code _c<channelId>} suffix without
 * forcing providers to know per-instance names.
 */
class ChannelToolCallbackTest {

    @Test
    @DisplayName("call delegates to the supplied handler")
    void callDelegates() {
        ChannelToolCallback cb = new ChannelToolCallback(
                "test_tool", "test description", "{\"type\":\"object\"}",
                in -> "echo:" + in);
        assertEquals("echo:hello", cb.call("hello"));
        assertEquals("echo:world", cb.call("world", null));
    }

    @Test
    @DisplayName("renamed returns a new callback carrying the same handler + description + schema")
    void renamedKeepsBehaviorWithNewName() {
        ChannelToolCallback original = new ChannelToolCallback(
                "feishu_doc_read", "read doc", "{}", in -> "READ:" + in);
        ToolCallback renamed = original.renamed("feishu_doc_read_c42");

        assertNotSame(original, renamed);
        assertEquals("feishu_doc_read_c42", renamed.getToolDefinition().name());
        assertEquals("read doc", renamed.getToolDefinition().description());
        assertEquals("READ:abc", renamed.call("abc"));
    }

    @Test
    @DisplayName("channel callbacks require the originating workspace context")
    void workspaceScopeRejectsCrossTenantCalls() {
        ChannelToolService service = new ChannelToolService(
                java.util.List.of(), null, null, null, null, null, null);
        ToolCallback scoped = service.workspaceScoped(new ChannelToolCallback(
                "feishu_doc_read", "read doc", "{}", in -> "ok"), 7L);

        assertEquals("ok", scoped.call("{}", ChatOrigin.web("c", "u", 7L, null).toToolContext()));
        org.junit.jupiter.api.Assertions.assertTrue(scoped.call(
                "{}", ChatOrigin.web("c", "u", 8L, null).toToolContext()).contains("matching workspace"));
        org.junit.jupiter.api.Assertions.assertTrue(scoped.call("{}").contains("matching workspace"));
    }
}

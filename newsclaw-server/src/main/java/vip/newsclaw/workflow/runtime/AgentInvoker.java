package vip.newsclaw.workflow.runtime;

import vip.newsclaw.agent.context.ChatOrigin;

/**
 * SPI for "render prompt → run agent → return text response". Kept thin so
 * unit tests can stub agent execution without booting the full StateGraph
 * runtime. Production binding lives in {@link DefaultAgentInvoker} and
 * delegates to {@code AgentService.chat(...)}.
 */
public interface AgentInvoker {

    /**
     * Invoke the resolved agent with {@code prompt} and return the agent's
     * final response text. {@code conversationId} is the ephemeral conversation
     * id created per workflow step — the runner generates this so each step
     * has its own conversational scope.
     */
    String invoke(long agentId, String prompt, String conversationId);

    /**
     * Origin-aware invocation. The default keeps existing test/embedding
     * implementations source-compatible while production can preserve tenant
     * and human-vs-cron identity all the way into tools.
     */
    default String invoke(long agentId, String prompt, String conversationId,
                          ChatOrigin origin) {
        return invoke(agentId, prompt, conversationId);
    }

    /**
     * Resolve a workspace-scoped agent name to its id. Returns {@code null}
     * when the agent does not exist or is disabled.
     */
    Long resolveAgentId(long workspaceId, String agentName);

    /**
     * Validate an id embedded in a published graph against its run workspace.
     * The default preserves source compatibility for lightweight test
     * implementations; the production binding performs the real lookup.
     */
    default Long resolveAgentId(long workspaceId, Long agentId) {
        return agentId;
    }
}

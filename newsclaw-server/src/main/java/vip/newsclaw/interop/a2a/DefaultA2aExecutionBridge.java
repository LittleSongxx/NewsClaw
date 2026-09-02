package vip.newsclaw.interop.a2a;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.newsclaw.agent.AgentService;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.agent.context.ChatOrigin;

@Service
@RequiredArgsConstructor
public class DefaultA2aExecutionBridge implements A2aExecutionBridge {

    private final AgentService agentService;

    /** Optional only to keep direct unit construction source-compatible. */
    private AgentMapper agentMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAgentMapper(AgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    @Override
    public ExecutionResult executeBlocking(A2aExecutionRequest request) {
        if (request == null || request.agentId() == null || request.workspaceId() == null) {
            throw new IllegalArgumentException("A2A agent and workspace are required");
        }
        if (agentMapper != null) {
            AgentEntity agent = agentMapper.selectById(request.agentId());
            long agentWorkspace = agent == null || agent.getWorkspaceId() == null
                    ? 1L : agent.getWorkspaceId();
            if (agent == null || (agent.getDeleted() != null && agent.getDeleted() != 0)
                    || Boolean.FALSE.equals(agent.getEnabled())
                    || agentWorkspace != request.workspaceId()) {
                throw new IllegalArgumentException("A2A agent is unavailable in the requested workspace");
            }
        }
        ChatOrigin origin = ChatOrigin.web(
                request.contextId(),
                request.username(),
                request.workspaceId(),
                null,
                null,
                request.userId()
        );
        AgentService.ChatResult result = agentService.chatWithUsage(
                request.agentId(),
                request.message(),
                request.contextId(),
                origin
        );
        return new ExecutionResult(result.content(), true);
    }
}

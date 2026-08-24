package vip.mate.llm.trace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;

/**
 * Best-effort persistence for the LLM routing control plane.
 *
 * <p>Tracing is deliberately non-blocking from the caller's point of view:
 * an unavailable observability table must never turn a recoverable model
 * fallback into a user-visible chat failure.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmRoutingTraceService {

    private final LlmRoutingTraceMapper traceMapper;

    @Autowired(required = false)
    private AgentMapper agentMapper;

    public void record(RoutingTrace trace) {
        if (trace == null) {
            return;
        }
        try {
            LlmRoutingTraceEntity entity = new LlmRoutingTraceEntity();
            entity.setWorkspaceId(trace.workspaceId() != null
                    ? trace.workspaceId() : resolveWorkspaceId(trace.agentId()));
            entity.setAgentId(trace.agentId());
            entity.setConversationId(trim(trace.conversationId(), 256));
            entity.setPhase(trim(trace.phase(), 128));
            entity.setRouteRole(trim(defaultValue(trace.routeRole(), "PRIMARY"), 32));
            entity.setProviderId(trim(trace.providerId(), 128));
            entity.setModelName(trim(trace.modelName(), 256));
            entity.setAttemptNo(Math.max(0, trace.attemptNo()));
            entity.setFallbackOrdinal(Math.max(0, trace.fallbackOrdinal()));
            entity.setOutcome(trim(defaultValue(trace.outcome(), "UNKNOWN"), 32));
            entity.setFailureCategory(trim(trace.failureCategory(), 64));
            entity.setDurationMs(Math.max(0L, trace.durationMs()));
            entity.setMetadataJson(trim(trace.metadataJson(), 32_000));
            entity.setDeleted(0);
            traceMapper.insert(entity);
        } catch (Exception e) {
            log.debug("[LlmRoutingTrace] Persist failed: {}", e.getMessage());
        }
    }

    private Long resolveWorkspaceId(Long agentId) {
        if (agentId == null || agentMapper == null) {
            return null;
        }
        try {
            AgentEntity agent = agentMapper.selectById(agentId);
            return agent == null ? null : agent.getWorkspaceId();
        } catch (Exception e) {
            return null;
        }
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record RoutingTrace(
            Long workspaceId,
            Long agentId,
            String conversationId,
            String phase,
            String routeRole,
            String providerId,
            String modelName,
            int attemptNo,
            int fallbackOrdinal,
            String outcome,
            String failureCategory,
            long durationMs,
            String metadataJson
    ) {
    }
}

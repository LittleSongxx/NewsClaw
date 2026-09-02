package vip.newsclaw.memory.fact.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import vip.newsclaw.memory.MemoryProperties;
import vip.newsclaw.memory.fact.model.FactContradictionEntity;
import vip.newsclaw.memory.fact.model.FactEntity;
import vip.newsclaw.memory.fact.query.FactQueryService;
import vip.newsclaw.memory.identity.MemoryOwnerResolver;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.agent.model.AgentEntity;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent tools for querying the fact projection.
 * Read-only — no fact_add / fact_remove / fact_update tools (core invariant D1).
 *
 * @author NewsClaw Team
 */
@Component
@RequiredArgsConstructor
public class FactQueryTool {

    private final FactQueryService queryService;
    private final MemoryProperties properties;

    private MemoryOwnerResolver ownerResolver;
    private AgentMapper agentMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOwnerResolver(MemoryOwnerResolver ownerResolver) {
        this.ownerResolver = ownerResolver;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAgentMapper(AgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    @Tool(description = "Probe facts about an entity. Returns relevant facts where the entity appears as subject or object.")
    public String fact_probe(
            @ToolParam(description = "Agent ID. Must be passed as a string to preserve large integer precision") String agentId,
            @ToolParam(description = "Entity name to search for") String entity,
            ToolContext toolContext) {
        if (!properties.getFact().isProjectionEnabled()) {
            return "Fact projection is disabled.";
        }
        Long parsedAgentId = parseAgentId(agentId);
        ChatOrigin origin = ChatOrigin.from(toolContext);
        if (origin.agentId() != null && !origin.agentId().equals(parsedAgentId)) {
            return "agentId does not match the current conversation context.";
        }
        if (!agentVisibleInWorkspace(parsedAgentId, origin)) {
            return "agentId is outside the current workspace.";
        }
        String ownerKey = ownerResolver == null ? null : ownerResolver.resolve(origin);
        List<FactEntity> facts = toolContext == null && ownerResolver == null
                ? queryService.probe(parsedAgentId, entity)
                : queryService.probe(parsedAgentId, entity, ownerKey);
        if (facts.isEmpty()) return "No facts found for entity: " + entity;

        // Bump use count
        queryService.bumpUseCount(facts.stream().map(FactEntity::getId).toList());

        return facts.stream()
                .map(f -> String.format("- %s %s %s (trust=%.2f)", f.getSubject(), f.getPredicate(), f.getObjectValue(), f.getTrust()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "List unresolved fact contradictions detected during Dream consolidation.")
    public String fact_list_contradictions(
            @ToolParam(description = "Agent ID. Must be passed as a string to preserve large integer precision") String agentId,
            ToolContext toolContext) {
        if (!properties.getFact().isProjectionEnabled()) {
            return "Fact projection is disabled.";
        }
        Long parsedAgentId = parseAgentId(agentId);
        ChatOrigin origin = ChatOrigin.from(toolContext);
        if (origin.agentId() != null && !origin.agentId().equals(parsedAgentId)) {
            return "agentId does not match the current conversation context.";
        }
        if (!agentVisibleInWorkspace(parsedAgentId, origin)) {
            return "agentId is outside the current workspace.";
        }
        String ownerKey = ownerResolver == null ? null : ownerResolver.resolve(origin);
        List<FactContradictionEntity> contradictions = toolContext == null && ownerResolver == null
                ? queryService.listContradictions(parsedAgentId)
                : queryService.listContradictions(parsedAgentId, ownerKey);
        if (contradictions.isEmpty()) return "No unresolved contradictions.";

        return contradictions.stream()
                .map(c -> String.format("- Contradiction #%d: factA=%d vs factB=%d — %s",
                        c.getId(), c.getFactAId(), c.getFactBId(),
                        c.getDescription() != null ? c.getDescription() : ""))
                .collect(Collectors.joining("\n"));
    }

    /** Source-compatible helpers for direct callers/tests. */
    public String fact_probe(String agentId, String entity) {
        return fact_probe(agentId, entity, null);
    }

    public String fact_list_contradictions(String agentId) {
        return fact_list_contradictions(agentId, null);
    }

    private static Long parseAgentId(String agentId) {
        String trimmed = agentId != null ? agentId.trim() : "";
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("agentId is required");
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("agentId must be a numeric string");
        }
    }

    private boolean agentVisibleInWorkspace(Long agentId, ChatOrigin origin) {
        if (origin == null || origin.workspaceId() == null || agentMapper == null) return true;
        AgentEntity agent = agentMapper.selectById(agentId);
        return agent != null && java.util.Objects.equals(agent.getWorkspaceId(), origin.workspaceId());
    }
}

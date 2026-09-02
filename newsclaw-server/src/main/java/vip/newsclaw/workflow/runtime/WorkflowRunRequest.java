package vip.newsclaw.workflow.runtime;

import vip.newsclaw.agent.context.ChatOrigin;

import java.util.Map;
import java.util.List;

/**
 * Inputs the runner needs to start a single workflow run. Identity fields
 * ({@code workflowId}, {@code revisionId}, {@code workspaceId}) tie the run
 * row back to the published revision the runner walks. {@code triggeredBy}
 * is a free-form label written into {@code mate_workflow_run.triggered_by}
 * — the runner doesn't interpret it.
 */
public record WorkflowRunRequest(
        long workflowId,
        long revisionId,
        long workspaceId,
        String triggeredBy,
        Map<String, Object> inputs,
        ChatOrigin origin,
        List<Long> triggerAncestry,
        int triggerDepth
) {
    /** Backward-compatible constructor for callers that do not have an inbound origin. */
    public WorkflowRunRequest(long workflowId, long revisionId, long workspaceId,
                              String triggeredBy, Map<String, Object> inputs) {
        this(workflowId, revisionId, workspaceId, triggeredBy, inputs,
                null, List.of(), 0);
    }

    /** Backward-compatible constructor for callers with an origin but no trigger chain. */
    public WorkflowRunRequest(long workflowId, long revisionId, long workspaceId,
                              String triggeredBy, Map<String, Object> inputs,
                              ChatOrigin origin) {
        this(workflowId, revisionId, workspaceId, triggeredBy, inputs,
                origin, List.of(), 0);
    }

    public WorkflowRunRequest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        triggerAncestry = triggerAncestry == null ? List.of() : List.copyOf(triggerAncestry);
        triggerDepth = Math.max(0, triggerDepth);
    }
}

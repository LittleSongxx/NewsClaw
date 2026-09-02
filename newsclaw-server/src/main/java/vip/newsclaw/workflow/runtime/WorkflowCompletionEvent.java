package vip.newsclaw.workflow.runtime;

import java.util.List;

/**
 * Spring application event fired when a workflow run reaches a terminal
 * state ({@code succeeded} / {@code failed}). The trigger module
 * subscribes via {@code @EventListener} and pushes the payload through
 * {@link vip.newsclaw.trigger.ingest.TriggerEventIngestService} so downstream
 * triggers (e.g. {@code workflow_completion} pattern) can chain off the
 * outcome.
 *
 * <p>Going through the event bus instead of injecting the trigger
 * service directly into the workflow runner breaks the
 * Runner ↔ Dispatcher ↔ Ingest ↔ Runner circular dependency that Spring
 * would otherwise refuse to construct.
 */
public record WorkflowCompletionEvent(
        long runId,
        long workflowId,
        long revisionId,
        long workspaceId,
        String state,
        String finalOutputRef,
        String errorMessage,
        List<Long> triggerAncestry,
        int triggerDepth
) {
    public WorkflowCompletionEvent(long runId, long workflowId, long revisionId,
                                   long workspaceId, String state,
                                   String finalOutputRef, String errorMessage) {
        this(runId, workflowId, revisionId, workspaceId, state,
                finalOutputRef, errorMessage, List.of(), 0);
    }

    public WorkflowCompletionEvent {
        triggerAncestry = triggerAncestry == null ? List.of() : List.copyOf(triggerAncestry);
        triggerDepth = Math.max(0, triggerDepth);
    }
}

package vip.newsclaw.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.newsclaw.workflow.compiler.ir.StepMode;
import vip.newsclaw.workflow.compiler.ir.WorkflowGraph;
import vip.newsclaw.workflow.compiler.ir.WorkflowStep;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.workflow.model.WorkflowRunEntity;
import vip.newsclaw.workflow.model.WorkflowRunStepEntity;
import vip.newsclaw.workflow.repository.WorkflowRunMapper;
import vip.newsclaw.workflow.repository.WorkflowRunStepMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Linear executor for v0 workflows. Walks the graph step-by-step, batching
 * adjacent {@code fan_out} steps + terminating {@code collect} into a single
 * parallel group. The first failed (non-skipped) step aborts the run and
 * marks the row {@code failed}. The last non-skipped step's output payload
 * is recorded as {@code final_output_ref} on success.
 *
 * <p><b>v0 runtime decision:</b> StateGraph is intentionally not used here.
 * The seven v0 modes (sequential / fan_out / collect / conditional +
 * await_approval / dispatch_channel / write_memory) are linear plus one
 * bounded parallel section, which this small executor handles more
 * directly than wrapping a graph DSL. {@code await_approval} pause / resume
 * is implemented via {@link WorkflowResumer} reading the persisted
 * {@code mate_workflow_run_pause} row, so a JVM restart still recovers the
 * run. v1 will reassess whether to graduate to a graph-backed scheduler
 * once {@code loop} / {@code invoke_skill} land — until then, "linear
 * executor" is the explicit, supported runtime.
 *
 * <p>StateGraph remains in use elsewhere for agent-internal control flow
 * (ReAct / Plan-Execute) — that's the runtime owned by
 * {@link vip.newsclaw.agent agent module}, not this workflow module.
 */
@Slf4j
@Service
public class WorkflowRunner {

    private static final String STATE_RUNNING = "running";
    private static final String STATE_SUCCEEDED = "succeeded";
    private static final String STATE_FAILED = "failed";
    private static final String STATE_SKIPPED = "skipped";
    private static final String STATE_PAUSED = "paused";

    private final ThreadPoolExecutor stepExecutor = new ThreadPoolExecutor(
            16, 16, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            r -> {
                Thread t = new Thread(r, "workflow-step");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private final WorkflowRunMapper runMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final StepAdapterRegistry adapters;
    private final PayloadStore payloadStore;
    /** Optional — wired in production, may be null in narrow test contexts.
     *  Spring's stock publisher is always available in a full context. */
    @Autowired(required = false)
    private ApplicationEventPublisher events;

    /** Optional domain gate; absent in lightweight workflow-only deployments. */
    @Autowired(required = false)
    private AiNewsEventService aiNewsEventService;

    /** Optional for narrow constructor-based tests; Spring supplies the
     * application mapper in production so the origin snapshot is durable. */
    @Autowired(required = false)
    private ObjectMapper objectMapper;

    public WorkflowRunner(WorkflowRunMapper runMapper,
                          WorkflowRunStepMapper stepMapper,
                          StepAdapterRegistry adapters,
                          PayloadStore payloadStore) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.adapters = adapters;
        this.payloadStore = payloadStore;
    }

    @PreDestroy
    void shutdownStepExecutor() {
        stepExecutor.shutdownNow();
    }

    public WorkflowRunResult run(WorkflowGraph graph, WorkflowRunRequest request) {
        ChatOrigin origin = effectiveOrigin(request);
        WorkflowRunEntity runRow = openRun(request, origin);
        String inputsRef = payloadStore.storeJson(request.workspaceId(), request.inputs());
        runRow.setInitialInputRef(inputsRef);
        runMapper.updateById(runRow);

        WorkflowRunContext ctx = new WorkflowRunContext(
                runRow.getId(),
                request.workspaceId(),
                request.workflowId(),
                request.revisionId(),
                request.inputs(),
                origin);

        return executeFromIndex(graph, ctx, runRow, /*fromIndex*/ 0, /*priorOutputRef*/ null);
    }

    private static ChatOrigin effectiveOrigin(WorkflowRunRequest request) {
        if (request.origin() != null) {
            return request.origin().withWorkspace(request.workspaceId(),
                    request.origin().workspaceBasePath());
        }
        // A missing origin is treated as a scheduled/system run. This is
        // deliberately fail-closed for human-only tools; it also keeps the
        // workflow tenant explicit instead of allowing tool fallback to 1L.
        return ChatOrigin.cron(null, request.workspaceId(), null, null, null);
    }

    /**
     * Continue an already-open run from {@code fromIndex}. Used by the resumer
     * after a pause settles. {@code priorOutputRef} is the last successful
     * step's output URI from before the pause — propagated so the
     * {@code final_output_ref} on success still points at meaningful data when
     * the post-resume tail of the run produces no further output.
     */
    public WorkflowRunResult continueFromIndex(WorkflowGraph graph, WorkflowRunContext ctx,
                                               WorkflowRunEntity runRow, int fromIndex,
                                               String priorOutputRef) {
        // WorkflowResumer atomically moved paused -> running together with
        // the pause/step claim. Do not write the stale full entity here.
        return executeFromIndex(graph, ctx, runRow, fromIndex, priorOutputRef);
    }

    private WorkflowRunResult executeFromIndex(WorkflowGraph graph, WorkflowRunContext ctx,
                                               WorkflowRunEntity runRow, int fromIndex,
                                               String priorOutputRef) {
        String lastSucceededOutputRef = priorOutputRef;
        try {
            int i = fromIndex;
            while (i < graph.steps().size()) {
                WorkflowStep step = graph.steps().get(i);
                int groupEnd = scanFanOutGroup(graph.steps(), i);
                if (groupEnd > i) {
                    GroupOutcome out = executeFanOutGroup(graph.steps(), i, groupEnd, ctx);
                    if (out.failed) {
                        return finishFailed(runRow, out.errorMessage);
                    }
                    if (out.lastOutputRef != null) lastSucceededOutputRef = out.lastOutputRef;
                    i = groupEnd + 1;
                } else {
                    StepResult result = executeStepTimed(step, i, /*iterationIndex*/ null, ctx);
                    if (result.state() == StepResult.State.FAILED) {
                        return finishFailed(runRow, result.errorMessage());
                    }
                    if (result.state() == StepResult.State.PAUSED) {
                        return finishPaused(runRow, result.pauseToken());
                    }
                    if (result.outputPayloadUri() != null) {
                        lastSucceededOutputRef = result.outputPayloadUri();
                    }
                    i++;
                }
            }
            return finishSucceeded(runRow, lastSucceededOutputRef);
        } catch (RuntimeException e) {
            log.error("Workflow run {} aborted by unexpected exception", ctx.runId(), e);
            return finishFailed(runRow, "runtime error: " + e.getMessage());
        }
    }

    /**
     * Result of executing a contiguous {@code fan_out ... collect} block:
     * either every branch succeeded (or skipped) and the merged outputs are
     * already in the run context, or one branch failed and the runner aborts.
     */
    private record GroupOutcome(boolean failed, String errorMessage, String lastOutputRef) {}

    private record FanOutBranch(int stepIndex, WorkflowStep step,
                                WorkflowRunContext branchCtx, WorkflowRunStepEntity row,
                                Future<StepResult> future, long deadlineNanos) {}

    /**
     * If {@code steps[start]} is the head of a fan_out group (≥ 2 consecutive
     * fan_out followed by exactly one collect — the schema validator already
     * enforced this), return the index of the terminating collect. Otherwise
     * return {@code start} so the caller treats it as a single-step.
     */
    private static int scanFanOutGroup(List<WorkflowStep> steps, int start) {
        if (!(steps.get(start).mode() instanceof StepMode.FanOut)) return start;
        int j = start;
        while (j < steps.size() && steps.get(j).mode() instanceof StepMode.FanOut) j++;
        if (j < steps.size() && steps.get(j).mode() instanceof StepMode.Collect) {
            return j;
        }
        return start;
    }

    private GroupOutcome executeFanOutGroup(List<WorkflowStep> steps, int from, int collectIdx,
                                            WorkflowRunContext ctx) {
        // Steps from..collectIdx-1 are fan_out branches; collectIdx is the join.
        //
        // RFC §2.4 requires every branch to render expressions / prompts
        // against the SAME context snapshot taken at group entry, with
        // collect doing the merge. To honour that we hand each branch its
        // own isolated WorkflowRunContext via branchSnapshot() — writes
        // inside a branch (via ctx.putOutput from executeStep) land in
        // that local copy and stay invisible to siblings until merge
        // time. Without this, a branch racing ahead would mutate the
        // shared outputs map and the slower branch's Pebble template
        // would observe a mid-flight value, making rendering
        // schedule-dependent.
        List<FanOutBranch> branches = new ArrayList<>();
        for (int i = from; i < collectIdx; i++) {
            int idx = i;
            WorkflowStep step = steps.get(i);
            WorkflowRunContext branchCtx = ctx.branchSnapshot();
            WorkflowRunStepEntity row = openStep(ctx.runId(), idx, idx - from, step);
            try {
                Future<StepResult> future = stepExecutor.submit(
                        () -> executeOpenedStep(step, branchCtx, row));
                branches.add(new FanOutBranch(idx, step, branchCtx, row, future,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(resolveTimeoutSecs(step))));
            } catch (RejectedExecutionException rejected) {
                closeStep(row, StepResult.failed("workflow executor busy"), 0);
                cancelBranches(branches, "fan_out cancelled after submit rejection");
                return new GroupOutcome(true,
                        "fan_out branch '" + step.name() + "' rejected: executor busy", null);
            }
        }

        // Collect succeeded branch results in step-index order. The
        // result list lets us merge outputs into the master context
        // deterministically below — a branch's outputVar always wins
        // over a smaller-index branch's outputVar with the same name,
        // so the conflict policy is "later step wins" and is independent
        // of completion order.
        record Settled(int stepIndex, WorkflowStep step, StepResult result) {}
        List<Settled> settled = new ArrayList<>(branches.size());
        for (FanOutBranch branch : branches) {
            try {
                long remaining = branch.deadlineNanos - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("step timeout");
                StepResult result = branch.future.get(remaining, TimeUnit.NANOSECONDS);
                if (result.state() == StepResult.State.FAILED) {
                    cancelBranches(branches, "fan_out cancelled after sibling failure");
                    return new GroupOutcome(true,
                            "fan_out branch '" + branch.step.name() + "' failed: " + result.errorMessage(),
                            null);
                }
                settled.add(new Settled(branch.stepIndex, branch.step, result));
            } catch (TimeoutException timeout) {
                branch.future.cancel(true);
                closeStep(branch.row, StepResult.failed("step timeout after "
                        + resolveTimeoutSecs(branch.step) + "s"),
                        TimeUnit.SECONDS.toMillis(resolveTimeoutSecs(branch.step)));
                cancelBranches(branches, "fan_out cancelled after sibling timeout");
                return new GroupOutcome(true,
                        "fan_out branch '" + branch.step.name() + "' timed out", null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancelBranches(branches, "fan_out interrupted");
                return new GroupOutcome(true,
                        "fan_out branch '" + branch.step.name() + "' interrupted", null);
            } catch (Exception e) {
                branch.future.cancel(true);
                cancelBranches(branches, "fan_out cancelled after sibling exception");
                return new GroupOutcome(true,
                        "fan_out branch '" + branch.step.name() + "' threw: " + e.getMessage(),
                        null);
            }
        }

        // Merge phase — the master context only learns about a branch's
        // outputVar value here, so collect (and any subsequent step)
        // sees a stable, schedule-independent view.
        String lastOutputRef = null;
        settled.sort((a, b) -> Integer.compare(a.stepIndex, b.stepIndex));
        for (Settled s : settled) {
            if (s.result.state() != StepResult.State.SUCCEEDED) continue;
            if (s.step.outputVar() != null && !s.step.outputVar().isBlank()
                    && s.result.outputValue() != null) {
                ctx.mergeOutput(s.step.outputVar(), s.result.outputValue());
            }
            if (s.result.outputPayloadUri() != null) lastOutputRef = s.result.outputPayloadUri();
        }

        // Run the collect adapter so the join is captured as its own row.
        StepResult collectResult = executeStepTimed(steps.get(collectIdx), collectIdx, null, ctx);
        if (collectResult.state() == StepResult.State.FAILED) {
            return new GroupOutcome(true, collectResult.errorMessage(), null);
        }
        return new GroupOutcome(false, null, lastOutputRef);
    }

    private static long resolveTimeoutSecs(WorkflowStep step) {
        if (step.timeoutSecs() == null || step.timeoutSecs() <= 0) return 600L;
        return step.timeoutSecs();
    }

    private StepResult executeStep(WorkflowStep step, int stepIndex, Integer iterationIndex,
                                   WorkflowRunContext ctx) {
        WorkflowRunStepEntity stepRow = openStep(ctx.runId(), stepIndex, iterationIndex, step);
        return executeOpenedStep(step, ctx, stepRow);
    }

    private StepResult executeStepTimed(WorkflowStep step, int stepIndex, Integer iterationIndex,
                                        WorkflowRunContext ctx) {
        WorkflowRunStepEntity row = openStep(ctx.runId(), stepIndex, iterationIndex, step);
        long timeoutSecs = resolveTimeoutSecs(step);
        Future<StepResult> future;
        try {
            future = stepExecutor.submit(() -> executeOpenedStep(step, ctx, row));
        } catch (RejectedExecutionException rejected) {
            StepResult failed = StepResult.failed("workflow executor busy");
            closeStep(row, failed, 0);
            return failed;
        }
        try {
            return future.get(timeoutSecs, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            StepResult failed = StepResult.failed("step timeout after " + timeoutSecs + "s");
            closeStep(row, failed, TimeUnit.SECONDS.toMillis(timeoutSecs));
            return failed;
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            StepResult failed = StepResult.failed("step interrupted");
            closeStep(row, failed, 0);
            return failed;
        } catch (Exception error) {
            future.cancel(true);
            StepResult failed = StepResult.failed("step threw: "
                    + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
            closeStep(row, failed, 0);
            return failed;
        }
    }

    private StepResult executeOpenedStep(WorkflowStep step, WorkflowRunContext ctx,
                                         WorkflowRunStepEntity stepRow) {
        StepAdapter adapter = adapters.get(step.mode().typeName());

        long startNanos = System.nanoTime();
        StepResult result;
        try {
            result = adapter.execute(step, ctx);
        } catch (RuntimeException e) {
            log.error("Adapter {} threw on run={} stepIndex={} step='{}'",
                    step.mode().typeName(), ctx.runId(), stepRow.getStepIndex(), step.name(), e);
            result = StepResult.failed("adapter threw: " + e.getMessage());
        }
        result = enforceNewsVerificationGate(step, result, ctx);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        // ctx.putOutput is synchronised internally so concurrent fan_out
        // branches can commit their results back to the shared run context
        // without external locking.
        if (result.state() == StepResult.State.SUCCEEDED && step.outputVar() != null
                && !step.outputVar().isBlank() && result.outputValue() != null) {
            ctx.putOutput(step.outputVar(), result.outputValue());
        }

        closeStep(stepRow, result, elapsedMs);
        return result;
    }

    private void cancelBranches(List<FanOutBranch> branches, String reason) {
        for (FanOutBranch branch : branches) {
            if (!branch.future().isDone()) {
                branch.future().cancel(true);
                closeStep(branch.row(), StepResult.failed(reason), 0);
            }
        }
    }

    /**
     * The generated AI-news graph uses {@code verification_result} as the
     * boundary before editorial fan-out.  Treat candidate-only/blocked output
     * as a hard stop, and verify the referenced event against the authoritative
     * event table when the news module is present.  This is intentionally in
     * the runner, after the agent result and before the next step, so a prompt
     * cannot accidentally turn a blocked candidate into a delivery.
     */
    private StepResult enforceNewsVerificationGate(WorkflowStep step,
                                                   StepResult result,
                                                   WorkflowRunContext ctx) {
        if (result == null || result.state() != StepResult.State.SUCCEEDED
                || step == null || !"verification_result".equals(step.outputVar())) {
            return result;
        }
        if (!(result.outputValue() instanceof java.util.Map<?, ?> output)) {
            return StepResult.failed("news verification gate blocked: structured event result required");
        }
        Object statusValue = output.get("status");
        String status = statusValue == null ? "" : String.valueOf(statusValue).trim()
                .toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (status.contains("candidate") || status.contains("blocked")
                || Boolean.TRUE.equals(output.get("blocked"))) {
            return StepResult.failed("news verification gate blocked candidate-only result");
        }
        Long eventId = numericId(output.get("eventId"));
        if (eventId == null) {
            return StepResult.failed("news verification gate blocked: eventId is required");
        }
        if (aiNewsEventService != null
                && !aiNewsEventService.isVerifiedForPublication(ctx.workspaceId(), eventId)) {
            return StepResult.failed("news verification gate blocked: event is not verified in this workspace");
        }
        if (aiNewsEventService == null
                && !Set.of("verified", "in_production", "published").contains(status)) {
            return StepResult.failed("news verification gate blocked: status is not verified");
        }
        return result;
    }

    private static Long numericId(Object value) {
        if (value instanceof Number number) return number.longValue() > 0 ? number.longValue() : null;
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private WorkflowRunEntity openRun(WorkflowRunRequest request, ChatOrigin origin) {
        WorkflowRunEntity row = new WorkflowRunEntity();
        row.setWorkflowId(request.workflowId());
        row.setRevisionId(request.revisionId());
        row.setWorkspaceId(request.workspaceId());
        row.setState(STATE_RUNNING);
        row.setTriggeredBy(request.triggeredBy());
        row.setTriggeredMeta(serializeTriggerMeta(request, origin));
        row.setStartedAt(LocalDateTime.now());
        runMapper.insert(row);
        return row;
    }

    /** Serialize the origin once at run creation so resume survives a JVM
     * restart without falling back to a cron/system identity. */
    private String serializeTriggerMeta(WorkflowRunRequest request, ChatOrigin origin) {
        if (origin == null) return null;
        try {
            ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
            // Keep the origin fields at the top level for rows written by
            // older releases, while also storing the explicit wrapper used by
            // the current resume path.  This is additive and lets mixed
            // deployments retain requester identity during rolling upgrades.
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    mapper.valueToTree(origin);
            root.set("origin", root.deepCopy());
            root.set("triggerAncestry", mapper.valueToTree(request.triggerAncestry()));
            root.put("triggerDepth", request.triggerDepth());
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            // A missing snapshot is safer than a partial/untrusted one:
            // WorkflowResumer deliberately falls back to a cron origin.
            log.warn("Workflow origin snapshot serialization failed: {}", e.getMessage());
            return null;
        }
    }

    private WorkflowRunResult finishSucceeded(WorkflowRunEntity runRow, String finalOutputRef) {
        LocalDateTime completedAt = LocalDateTime.now();
        if (runMapper.finishRunning(runRow.getId(), STATE_SUCCEEDED,
                finalOutputRef, null, completedAt) != 1) {
            WorkflowRunEntity current = runMapper.selectById(runRow.getId());
            return new WorkflowRunResult(runRow.getId(),
                    current == null ? STATE_FAILED : current.getState(),
                    current == null ? null : current.getFinalOutputRef(),
                    current == null ? "workflow run disappeared" : current.getErrorMessage());
        }
        runRow.setState(STATE_SUCCEEDED);
        runRow.setFinalOutputRef(finalOutputRef);
        runRow.setCompletedAt(completedAt);
        publishCompletionEvent(runRow, STATE_SUCCEEDED, finalOutputRef, null);
        return new WorkflowRunResult(runRow.getId(), STATE_SUCCEEDED, finalOutputRef, null);
    }

    private WorkflowRunResult finishFailed(WorkflowRunEntity runRow, String errorMessage) {
        LocalDateTime completedAt = LocalDateTime.now();
        if (runMapper.finishRunning(runRow.getId(), STATE_FAILED,
                null, errorMessage, completedAt) != 1) {
            WorkflowRunEntity current = runMapper.selectById(runRow.getId());
            return new WorkflowRunResult(runRow.getId(),
                    current == null ? STATE_FAILED : current.getState(),
                    current == null ? null : current.getFinalOutputRef(),
                    current == null ? errorMessage : current.getErrorMessage());
        }
        runRow.setState(STATE_FAILED);
        runRow.setErrorMessage(errorMessage);
        runRow.setCompletedAt(completedAt);
        publishCompletionEvent(runRow, STATE_FAILED, null, errorMessage);
        return new WorkflowRunResult(runRow.getId(), STATE_FAILED, null, errorMessage);
    }

    /**
     * Fire a {@code workflow_completion} event into the trigger pipeline so
     * downstream workflows (or workflows reacting to upstream success /
     * failure) can chain off this run. Synchronous and best-effort: a
     * fan-out failure here MUST NOT corrupt the just-completed run state.
     *
     * <p>The eventId is keyed on {@code wf-run-{runId}} so a retry of the
     * same run never duplicate-fires its completion downstream — the
     * mate_trigger_event UNIQUE(trigger_id, dedup_key) constraint catches
     * any redundant publish at insert time.
     *
     * <p>Package-private so {@link WorkflowResumer} can publish the same
     * event for resumed runs that end on a rejected / timed-out approval
     * (those don't go through {@link #finishFailed} since the resumer
     * writes terminal state directly).
     */
    void publishCompletionEvent(WorkflowRunEntity runRow, String state,
                                String finalOutputRef, String errorMessage) {
        if (events == null || runRow == null) return;
        try {
            TriggerLineage lineage = triggerLineage(runRow);
            events.publishEvent(new WorkflowCompletionEvent(
                    runRow.getId(),
                    runRow.getWorkflowId() == null ? 0L : runRow.getWorkflowId(),
                    runRow.getRevisionId() == null ? 0L : runRow.getRevisionId(),
                    runRow.getWorkspaceId() == null ? 0L : runRow.getWorkspaceId(),
                    state,
                    finalOutputRef,
                    errorMessage,
                    lineage.ancestry(), lineage.depth()));
        } catch (Exception e) {
            log.warn("Workflow run {} completion event publish failed: {}",
                    runRow.getId(), e.getMessage());
        }
    }

    private TriggerLineage triggerLineage(WorkflowRunEntity runRow) {
        if (runRow == null || runRow.getTriggeredMeta() == null
                || runRow.getTriggeredMeta().isBlank()) {
            return new TriggerLineage(List.of(), 0);
        }
        try {
            ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
            var node = mapper.readTree(runRow.getTriggeredMeta());
            List<Long> ancestry = new ArrayList<>();
            var values = node.path("triggerAncestry");
            if (values.isArray()) {
                values.forEach(value -> {
                    if (value.canConvertToLong()) ancestry.add(value.asLong());
                });
            }
            return new TriggerLineage(List.copyOf(ancestry),
                    Math.max(0, node.path("triggerDepth").asInt(0)));
        } catch (Exception ignored) {
            return new TriggerLineage(List.of(), 0);
        }
    }

    private record TriggerLineage(List<Long> ancestry, int depth) {}

    private WorkflowRunResult finishPaused(WorkflowRunEntity runRow, String pauseToken) {
        if (runMapper.pauseRunning(runRow.getId()) != 1) {
            WorkflowRunEntity current = runMapper.selectById(runRow.getId());
            return new WorkflowRunResult(runRow.getId(),
                    current == null ? STATE_FAILED : current.getState(), null,
                    current == null ? "workflow run disappeared" : current.getErrorMessage());
        }
        runRow.setState(STATE_PAUSED);
        return new WorkflowRunResult(runRow.getId(), STATE_PAUSED, null, "pauseToken=" + pauseToken);
    }

    private WorkflowRunStepEntity openStep(long runId, int stepIndex, Integer iterationIndex,
                                           WorkflowStep step) {
        WorkflowRunStepEntity row = new WorkflowRunStepEntity();
        row.setRunId(runId);
        row.setStepIndex(stepIndex);
        row.setIterationIndex(iterationIndex);
        row.setStepName(step.name());
        row.setAgentId(step.agentId());
        row.setState(STATE_RUNNING);
        row.setOutputContentType(step.effectiveOutputContentType());
        row.setStartedAt(LocalDateTime.now());
        stepMapper.insert(row);
        return row;
    }

    private void closeStep(WorkflowRunStepEntity row, StepResult result, long durationMs) {
        String state = switch (result.state()) {
            case SUCCEEDED -> STATE_SUCCEEDED;
            case SKIPPED -> STATE_SKIPPED;
            case FAILED -> STATE_FAILED;
            case PAUSED -> STATE_PAUSED;
        };
        LocalDateTime completedAt = LocalDateTime.now();
        int changed = stepMapper.closeRunning(row.getId(), state,
                result.outputPayloadUri(),
                result.outputContentType() != null
                        ? result.outputContentType() : row.getOutputContentType(),
                result.outputSummary(), result.errorMessage(), durationMs, completedAt);
        if (changed == 1) {
            row.setState(state);
            row.setOutputRef(result.outputPayloadUri());
            row.setOutputSummary(result.outputSummary());
            row.setErrorMessage(result.errorMessage());
            row.setDurationMs(durationMs);
            row.setCompletedAt(completedAt);
        } else {
            log.debug("Workflow step {} terminal CAS lost; late result ignored", row.getId());
        }
    }

}

package vip.newsclaw.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import vip.newsclaw.workflow.compiler.ir.WorkflowGraph;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.workflow.model.WorkflowRunEntity;
import vip.newsclaw.workflow.model.WorkflowRunPauseEntity;
import vip.newsclaw.workflow.model.WorkflowRunStepEntity;
import vip.newsclaw.workflow.repository.WorkflowRunMapper;
import vip.newsclaw.workflow.repository.WorkflowRunPauseMapper;
import vip.newsclaw.workflow.repository.WorkflowRunStepMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Settles a paused workflow run. Callers (approval callbacks, timeout sweeper,
 * REST endpoints) hand in a {@code pauseToken} and an outcome; the resumer
 * marks the pause and the await_approval step row, hydrates a fresh
 * {@link WorkflowRunContext} from the persisted step rows, and delegates back
 * to {@link WorkflowRunner#continueFromIndex} for the post-pause tail.
 *
 * <p>Idempotent: a pause that has already been resumed yields
 * {@link Outcome#alreadyResolved(long)} without touching DB or memory. The
 * graph is loaded by the caller (typically via a revision-id lookup) since the
 * resumer has no opinion on storage.
 */
@Slf4j
@Service
public class WorkflowResumer {

    private static final String STATE_SUCCEEDED = "succeeded";
    private static final String STATE_FAILED = "failed";

    private final WorkflowRunMapper runMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowRunPauseMapper pauseMapper;
    private final WorkflowRunner runner;
    private final PayloadStore payloadStore;
    private final ObjectMapper objectMapper;

    /**
     * Optional for the small mapper-only tests that construct this bean by
     * hand.  In a real Spring context this lets us keep the claim/settlement
     * transaction short and run the post-resume tail outside a JDBC
     * transaction (the tail may contain minutes of LLM/tool work).
     */
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public WorkflowResumer(WorkflowRunMapper runMapper,
                           WorkflowRunStepMapper stepMapper,
                           WorkflowRunPauseMapper pauseMapper,
                           WorkflowRunner runner,
                           PayloadStore payloadStore,
                           ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.pauseMapper = pauseMapper;
        this.runner = runner;
        this.payloadStore = payloadStore;
        this.objectMapper = objectMapper;
    }

    public Outcome resume(WorkflowGraph graph, String pauseToken,
                          ResumeOutcome outcome, byte[] resumePayloadBody) {
        PreparedResume prepared;
        if (transactionManager == null) {
            // Lightweight unit tests do not load a transaction manager. Keep
            // their deterministic in-process behavior while production uses
            // the short transaction below.
            prepared = claimAndPrepare(graph, pauseToken, outcome, resumePayloadBody);
        } else {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            prepared = tx.execute(status -> claimAndPrepare(
                    graph, pauseToken, outcome, resumePayloadBody));
            if (prepared == null) {
                return Outcome.notFound(pauseToken);
            }
        }
        if (prepared.terminalOutcome != null) {
            return prepared.terminalOutcome;
        }

        // The durable pause/step/run transition is committed before entering
        // the potentially long post-resume tail. A crash here leaves the run
        // in running state; the existing workflow recovery/reconciliation
        // path can pick it up without holding a stale DB connection.
        WorkflowRunResult result = runner.continueFromIndex(
                graph, prepared.context, prepared.runRow,
                prepared.nextStepIndex, prepared.priorOutputRef);
        return Outcome.continued(result);
    }

    /** Prepared state handed from the short transaction to the tail runner. */
    private static final class PreparedResume {
        private final Outcome terminalOutcome;
        private final WorkflowRunContext context;
        private final WorkflowRunEntity runRow;
        private final int nextStepIndex;
        private final String priorOutputRef;

        private PreparedResume(Outcome terminalOutcome, WorkflowRunContext context,
                               WorkflowRunEntity runRow, int nextStepIndex,
                               String priorOutputRef) {
            this.terminalOutcome = terminalOutcome;
            this.context = context;
            this.runRow = runRow;
            this.nextStepIndex = nextStepIndex;
            this.priorOutputRef = priorOutputRef;
        }

        static PreparedResume terminal(Outcome outcome) {
            return new PreparedResume(outcome, null, null, 0, null);
        }

        static PreparedResume tail(WorkflowRunContext context, WorkflowRunEntity runRow,
                                   int nextStepIndex, String priorOutputRef) {
            return new PreparedResume(null, context, runRow, nextStepIndex, priorOutputRef);
        }
    }

    /** Claim and settle the pause in one short transaction. */
    private PreparedResume claimAndPrepare(WorkflowGraph graph, String pauseToken,
                                           ResumeOutcome outcome, byte[] resumePayloadBody) {
        if (pauseToken == null || pauseToken.isBlank() || outcome == null) {
            return PreparedResume.terminal(Outcome.notFound(pauseToken));
        }
        WorkflowRunPauseEntity pause = pauseMapper.selectOne(new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                .eq(WorkflowRunPauseEntity::getPauseToken, pauseToken));
        if (pause == null) {
            return PreparedResume.terminal(Outcome.notFound(pauseToken));
        }
        if (pause.getResumedAt() != null) {
            return PreparedResume.terminal(Outcome.alreadyResolved(pause.getRunId()));
        }

        WorkflowRunEntity runRow = runMapper.selectById(pause.getRunId());
        if (runRow == null) {
            return PreparedResume.terminal(Outcome.notFound(pauseToken));
        }

        WorkflowRunStepEntity stepRow = stepMapper.selectById(pause.getStepId());
        if (stepRow == null) {
            return PreparedResume.terminal(Outcome.notFound(pauseToken));
        }

        // Persist the optional payload before claiming the pause.  This keeps
        // the existing resume contract (a storage failure leaves the pause
        // open for retry), while the conditional update below is the actual
        // fencing point that decides which concurrent callback owns the tail.
        String resumePayloadRef = null;
        if (resumePayloadBody != null && resumePayloadBody.length > 0) {
            resumePayloadRef = payloadStore.storeBytes(runRow.getWorkspaceId(),
                    resumePayloadBody, "application/octet-stream");
        }
        LocalDateTime resumedAt = LocalDateTime.now();
        int claimed = pauseMapper.claimResume(
                pause.getId(), pauseToken, resumedAt, outcome.token(), resumePayloadRef);
        if (claimed != 1) {
            // Another approval/timeout callback won the token race.  Do not
            // touch the step/run rows and, most importantly, do not execute
            // the post-resume tail a second time.
            return PreparedResume.terminal(Outcome.alreadyResolved(pause.getRunId()));
        }
        // Keep the in-memory entity coherent for callers/logging below.  The
        // durable write has already happened through the CAS mapper method;
        // calling updateById here would re-open the race we just fenced.
        pause.setResumedAt(resumedAt);
        pause.setResumeOutcome(outcome.token());
        pause.setResumePayloadRef(resumePayloadRef);

        // Pause claim, paused-step settlement and paused-run transition share
        // this transaction. A crash/exception before the tail commits rolls
        // the claim back, leaving the same token retryable after restart.
        String stepState = outcome == ResumeOutcome.APPROVED ? STATE_SUCCEEDED : STATE_FAILED;
        String stepError = outcome == ResumeOutcome.APPROVED
                ? null : "approval " + outcome.token();
        int stepChanged = stepMapper.settlePaused(stepRow.getId(), stepState,
                "resumed: " + outcome.token(), stepError, LocalDateTime.now());
        if (stepChanged != 1) {
            throw new IllegalStateException("paused workflow step was concurrently changed: " + stepRow.getId());
        }
        stepRow.setState(stepState);
        stepRow.setOutputSummary("resumed: " + outcome.token());
        stepRow.setCompletedAt(LocalDateTime.now());
        stepRow.setErrorMessage(stepError);

        if (outcome != ResumeOutcome.APPROVED) {
            // Failed approval ends the run — no further steps.
            String runError = "paused step '" + stepRow.getStepName() + "' " + outcome.token();
            LocalDateTime completedAt = LocalDateTime.now();
            if (runMapper.failPaused(runRow.getId(), runError, completedAt) != 1) {
                throw new IllegalStateException("paused workflow run was concurrently changed: " + runRow.getId());
            }
            runRow.setState(STATE_FAILED);
            runRow.setErrorMessage(runError);
            runRow.setCompletedAt(completedAt);
            // Publish the workflow_completion event downstream — same as the
            // runner's finishFailed path. Without this, runs that end on a
            // rejected / timed-out approval would never fire their
            // completion trigger because the resumer skips
            // runner.continueFromIndex on the failure branch.
            runner.publishCompletionEvent(runRow, STATE_FAILED, null, runRow.getErrorMessage());
            return PreparedResume.terminal(Outcome.failed(runRow.getId(), runRow.getErrorMessage()));
        }

        if (runMapper.resumePaused(runRow.getId()) != 1) {
            throw new IllegalStateException("paused workflow run was concurrently changed: " + runRow.getId());
        }
        runRow.setState("running");

        // Hydrate the run context from prior step rows so post-resume steps can
        // reference {{ outputs.xxx }} from steps that completed before the pause.
        WorkflowRunContext ctx = hydrateContext(runRow, graph, stepRow.getStepIndex());
        String priorOutputRef = lastSucceededOutputRef(runRow.getId(), stepRow.getStepIndex());

        return PreparedResume.tail(ctx, runRow, stepRow.getStepIndex() + 1, priorOutputRef);
    }

    private WorkflowRunContext hydrateContext(WorkflowRunEntity runRow, WorkflowGraph graph,
                                              int pausedStepIndex) {
        Map<String, Object> inputs = (runRow.getInitialInputRef() == null)
                ? Map.of()
                : payloadStore.readJson(runRow.getInitialInputRef(), Map.class);
        WorkflowRunContext ctx = new WorkflowRunContext(
                runRow.getId(),
                runRow.getWorkspaceId(),
                runRow.getWorkflowId(),
                runRow.getRevisionId(),
                inputs,
                restoreOrigin(runRow));

        // Replay the rolling outputs map: walk completed succeeded step rows
        // up to the pause and put their parsed payloads back into the context
        // under their declared outputVar.
        List<WorkflowRunStepEntity> rows = stepMapper.selectList(new LambdaQueryWrapper<WorkflowRunStepEntity>()
                .eq(WorkflowRunStepEntity::getRunId, runRow.getId())
                .lt(WorkflowRunStepEntity::getStepIndex, pausedStepIndex)
                .orderByAsc(WorkflowRunStepEntity::getStepIndex)
                .orderByAsc(WorkflowRunStepEntity::getIterationIndex));
        for (WorkflowRunStepEntity row : rows) {
            if (!STATE_SUCCEEDED.equals(row.getState()) || row.getOutputRef() == null) continue;
            int idx = row.getStepIndex();
            if (idx < 0 || idx >= graph.steps().size()) continue;
            var step = graph.steps().get(idx);
            if (step.outputVar() == null || step.outputVar().isBlank()) continue;
            Object value = decodeOutput(row);
            if (value != null) ctx.putOutput(step.outputVar(), value);
        }
        return ctx;
    }

    /**
     * Restore the origin snapshot written by {@link WorkflowRunner}. Old run
     * rows (or rows whose metadata was malformed) intentionally fall back to a
     * cron/system origin, which keeps human-only actions fail-closed.
     */
    private ChatOrigin restoreOrigin(WorkflowRunEntity runRow) {
        ChatOrigin fallback = ChatOrigin.cron(null, runRow.getWorkspaceId(), null, null, null);
        String metadata = runRow.getTriggeredMeta();
        if (metadata == null || metadata.isBlank()) return fallback;
        try {
            ObjectMapper mapper = mapper();
            // The current writer stores {"origin":{...},"triggerAncestry":...}.
            // Inspect the tree first: ChatOrigin deliberately ignores unknown
            // properties, so deserializing the wrapper directly would silently
            // produce an empty origin and lose the authenticated identity.
            var node = mapper.readTree(metadata);
            ChatOrigin origin;
            if (node != null && node.has("origin") && !node.get("origin").isNull()) {
                origin = mapper.treeToValue(node.get("origin"), ChatOrigin.class);
            } else {
                // Accept legacy rows that contain the origin object directly.
                origin = mapper.treeToValue(node, ChatOrigin.class);
            }
            if (!hasOriginData(origin)) return fallback;
            return origin.withWorkspace(runRow.getWorkspaceId(), origin.workspaceBasePath());
        } catch (Exception invalidSnapshot) {
            log.warn("Workflow resume: invalid origin snapshot for run {}: {}",
                    runRow.getId(), invalidSnapshot.getMessage());
            return fallback;
        }
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : new ObjectMapper();
    }

    private static boolean hasOriginData(ChatOrigin origin) {
        if (origin == null || origin.cronOrigin()) return origin != null;
        return origin.workspaceId() != null
                || origin.agentId() != null
                || origin.conversationId() != null
                || origin.requesterId() != null
                || origin.channelId() != null
                || origin.channelTarget() != null
                || origin.senderName() != null
                || origin.channelType() != null
                || origin.chatId() != null
                || origin.baseUrl() != null
                || origin.requesterUserId() != null
                || origin.originMessageId() != null;
    }

    private Object decodeOutput(WorkflowRunStepEntity row) {
        try {
            byte[] body = payloadStore.readBytes(row.getOutputRef());
            if ("json".equals(row.getOutputContentType())) {
                return mapper().readValue(body, Object.class);
            }
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Workflow resume: failed to decode prior step output ref={}: {}",
                    row.getOutputRef(), e.getMessage());
            return null;
        }
    }

    private String lastSucceededOutputRef(long runId, int beforeStepIndex) {
        WorkflowRunStepEntity row = stepMapper.selectOne(new LambdaQueryWrapper<WorkflowRunStepEntity>()
                .eq(WorkflowRunStepEntity::getRunId, runId)
                .eq(WorkflowRunStepEntity::getState, STATE_SUCCEEDED)
                .lt(WorkflowRunStepEntity::getStepIndex, beforeStepIndex)
                .isNotNull(WorkflowRunStepEntity::getOutputRef)
                .orderByDesc(WorkflowRunStepEntity::getStepIndex)
                .orderByDesc(WorkflowRunStepEntity::getIterationIndex)
                .last("LIMIT 1"));
        return row == null ? null : row.getOutputRef();
    }

    /** Outcome label written to {@code mate_workflow_run_pause.resume_outcome}. */
    public enum ResumeOutcome {
        APPROVED("approved"),
        REJECTED("rejected"),
        TIMEOUT("timeout"),
        CANCELLED("cancelled");

        private final String token;

        ResumeOutcome(String token) { this.token = token; }

        public String token() { return token; }
    }

    /** Result of attempting a resume — exposes the final run state when completed inline. */
    public record Outcome(Kind kind, Long runId, WorkflowRunResult finalResult, String errorMessage) {
        public enum Kind { CONTINUED, FAILED, ALREADY_RESOLVED, NOT_FOUND }

        public static Outcome continued(WorkflowRunResult r) {
            return new Outcome(Kind.CONTINUED, r.runId(), r, null);
        }
        public static Outcome failed(long runId, String err) {
            return new Outcome(Kind.FAILED, runId, null, err);
        }
        public static Outcome alreadyResolved(long runId) {
            return new Outcome(Kind.ALREADY_RESOLVED, runId, null, null);
        }
        public static Outcome notFound(String token) {
            return new Outcome(Kind.NOT_FOUND, null, null, "pause token not found: " + token);
        }
    }
}

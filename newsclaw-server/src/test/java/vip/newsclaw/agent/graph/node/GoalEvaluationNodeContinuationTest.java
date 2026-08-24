package vip.newsclaw.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import vip.newsclaw.agent.context.ConversationWindowManager;
import vip.newsclaw.agent.graph.state.FinishReason;
import vip.newsclaw.agent.graph.state.NewsClawStateKeys;
import vip.newsclaw.goal.config.GoalProperties;
import vip.newsclaw.goal.model.GoalEntity;
import vip.newsclaw.goal.model.GoalEvaluationResult;
import vip.newsclaw.goal.model.GoalResponse;
import vip.newsclaw.goal.service.GoalEvaluationService;
import vip.newsclaw.goal.service.GoalFollowupService;
import vip.newsclaw.goal.service.GoalService;
import vip.newsclaw.goal.service.GraphFlavor;
import vip.newsclaw.workspace.conversation.ConversationService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the goal self-continuation behaviour on terminal turns:
 * <ul>
 *   <li>MAX_ITERATIONS_REACHED now continues with a FRESH iteration budget
 *       ("hard continuation") instead of skipping silently.</li>
 *   <li>EVIDENCE_INSUFFICIENT now continues (corrective follow-up) without a
 *       budget reset.</li>
 *   <li>STOPPED / RETURN_DIRECT / ERROR_FALLBACK still skip.</li>
 *   <li>The hard-continuation cap bounds the fresh-budget loop.</li>
 * </ul>
 */
class GoalEvaluationNodeContinuationTest {

    // ===== Pure decision helpers =====

    @Test
    void hardSkip_coversUserAndNonProgressTerminals_only() {
        assertTrue(GoalEvaluationNode.isHardSkipFinishReason(FinishReason.STOPPED.getValue()));
        assertTrue(GoalEvaluationNode.isHardSkipFinishReason(FinishReason.RETURN_DIRECT.getValue()));
        assertTrue(GoalEvaluationNode.isHardSkipFinishReason(FinishReason.ERROR_FALLBACK.getValue()));
        // The two that must now fall through to evaluation:
        assertFalse(GoalEvaluationNode.isHardSkipFinishReason(FinishReason.MAX_ITERATIONS_REACHED.getValue()));
        assertFalse(GoalEvaluationNode.isHardSkipFinishReason(FinishReason.EVIDENCE_INSUFFICIENT.getValue()));
        assertFalse(GoalEvaluationNode.isHardSkipFinishReason(FinishReason.NORMAL.getValue()));
    }

    @Test
    void iterationCapReached_onlyForMaxIterations() {
        assertTrue(GoalEvaluationNode.isIterationCapReached(FinishReason.MAX_ITERATIONS_REACHED.getValue()));
        assertFalse(GoalEvaluationNode.isIterationCapReached(FinishReason.EVIDENCE_INSUFFICIENT.getValue()));
        assertFalse(GoalEvaluationNode.isIterationCapReached(FinishReason.NORMAL.getValue()));
    }

    // ===== resolveActiveGoal: same-turn activation =====

    @Test
    void resolveActiveGoal_prefersStateSnapshot_noDbLookup() {
        GoalService goalService = mock(GoalService.class);
        GoalEntity snap = new GoalEntity();
        snap.setId(7L);
        OverAllState s = new OverAllState(Map.of(
                NewsClawStateKeys.ACTIVE_GOAL, snap,
                NewsClawStateKeys.CONVERSATION_ID, "c1"));

        Optional<GoalEntity> out = GoalEvaluationNode.resolveActiveGoal(s, goalService);

        assertTrue(out.isPresent());
        assertEquals(7L, out.get().getId());
        // Snapshot hit must not touch the DB.
        verify(goalService, never()).findActiveByConversation(anyString());
    }

    @Test
    void resolveActiveGoal_fallsBackToDb_whenSnapshotEmpty() {
        GoalService goalService = mock(GoalService.class);
        GoalEntity fromDb = new GoalEntity();
        fromDb.setId(9L);
        when(goalService.findActiveByConversation("c1")).thenReturn(fromDb);
        OverAllState s = new OverAllState(Map.of(NewsClawStateKeys.CONVERSATION_ID, "c1"));

        Optional<GoalEntity> out = GoalEvaluationNode.resolveActiveGoal(s, goalService);

        assertTrue(out.isPresent(), "a goal set mid-turn must be found via the DB fallback");
        assertEquals(9L, out.get().getId());
    }

    @Test
    void resolveActiveGoal_emptyWhenNoSnapshotNoDbGoal() {
        GoalService goalService = mock(GoalService.class);
        when(goalService.findActiveByConversation(anyString())).thenReturn(null);
        OverAllState s = new OverAllState(Map.of(NewsClawStateKeys.CONVERSATION_ID, "c1"));
        assertTrue(GoalEvaluationNode.resolveActiveGoal(s, goalService).isEmpty());
    }

    // ===== apply() behaviour =====

    @Test
    void maxIterations_injectsFollowup_andResetsIterationBudget() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> out = f.node().apply(
                f.state(FinishReason.MAX_ITERATIONS_REACHED.getValue(), 0, 0));

        // Followup injected for the run-to-completion loop.
        assertEquals(Boolean.TRUE, out.get(NewsClawStateKeys.GOAL_FOLLOWUP_INJECTED));
        assertEquals(1, out.get(NewsClawStateKeys.GOAL_FOLLOWUP_COUNT));
        // Hard continuation: fresh ReAct segment.
        assertEquals(0, out.get(NewsClawStateKeys.CURRENT_ITERATION));
        assertEquals(1, out.get(NewsClawStateKeys.GOAL_HARD_CONTINUATION_COUNT));
        // Stale limit-exceeded draft/flag cleared so it can't resurface.
        assertEquals("", out.get(NewsClawStateKeys.FINAL_ANSWER_DRAFT));
        assertEquals(Boolean.FALSE, out.get(NewsClawStateKeys.LIMIT_EXCEEDED));
        assertEquals("", out.get(NewsClawStateKeys.FINAL_ANSWER));
        assertEquals("", out.get(NewsClawStateKeys.FINISH_REASON));
        // Not a terminal pass — the next answer must be re-evaluated.
        assertFalse(Boolean.TRUE.equals(out.get(NewsClawStateKeys.GOAL_EVALUATED_THIS_RUN)));
        // Followup appended as a user message.
        @SuppressWarnings("unchecked")
        List<Message> msgs = (List<Message>) out.get(NewsClawStateKeys.MESSAGES);
        assertEquals(1, msgs.size());
        assertInstanceOf(UserMessage.class, msgs.get(0));
    }

    @Test
    void evidenceInsufficient_injectsFollowup_withoutBudgetReset() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> out = f.node().apply(
                f.state(FinishReason.EVIDENCE_INSUFFICIENT.getValue(), 0, 0));

        assertEquals(Boolean.TRUE, out.get(NewsClawStateKeys.GOAL_FOLLOWUP_INJECTED));
        // No iteration reset / hard-continuation accounting on this path.
        assertFalse(out.containsKey(NewsClawStateKeys.CURRENT_ITERATION));
        assertFalse(out.containsKey(NewsClawStateKeys.GOAL_HARD_CONTINUATION_COUNT));
        assertFalse(out.containsKey(NewsClawStateKeys.FINAL_ANSWER_DRAFT));
    }

    @Test
    void stopped_skipsEvaluationEntirely() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> out = f.node().apply(
                f.state(FinishReason.STOPPED.getValue(), 0, 0));

        assertEquals(Boolean.TRUE, out.get(NewsClawStateKeys.GOAL_EVALUATED_THIS_RUN));
        assertFalse(out.containsKey(NewsClawStateKeys.GOAL_FOLLOWUP_INJECTED));
        // Evaluator must not even be called on a user-stopped turn.
        verify(f.evaluationService, never()).evaluate(any(), anyList(), anyString());
    }

    @Test
    void maxIterations_hardCapReached_endsRunWithoutReset() throws Exception {
        Fixture f = new Fixture();
        // hardContinuationCount already at the cap (default cap = 1).
        Map<String, Object> out = f.node().apply(
                f.state(FinishReason.MAX_ITERATIONS_REACHED.getValue(), 0, 1));

        // Falls through to the terminal "continue, no followup" path.
        assertEquals(Boolean.TRUE, out.get(NewsClawStateKeys.GOAL_EVALUATED_THIS_RUN));
        assertFalse(out.containsKey(NewsClawStateKeys.GOAL_FOLLOWUP_INJECTED));
        assertFalse(out.containsKey(NewsClawStateKeys.CURRENT_ITERATION));
    }

    // ===== Test fixture =====

    private static final class Fixture {
        final GoalEvaluationService evaluationService = mock(GoalEvaluationService.class);
        final GoalFollowupService followupService = mock(GoalFollowupService.class);
        final GoalService goalService = mock(GoalService.class);
        final GoalProperties properties = new GoalProperties();
        final ConversationWindowManager windowManager = mock(ConversationWindowManager.class);
        final ConversationService conversationService = mock(ConversationService.class);

        Fixture() {
            GoalEntity goal = new GoalEntity();
            goal.setId(1L);
            goal.setTitle("ship the feature");

            GoalEvaluationResult continueResult = new GoalEvaluationResult(
                    0.5, "missing tests", GoalEvaluationResult.DECISION_CONTINUE, false,
                    "stub-model", 1, 5L, List.of(), null);

            lenient().when(evaluationService.evaluate(any(), anyList(), anyString()))
                    .thenReturn(continueResult);
            lenient().when(goalService.getById(eq(1L))).thenReturn(goal);
            lenient().when(goalService.isBudgetExhausted(any())).thenReturn(false);
            lenient().when(goalService.toResponse(any())).thenReturn(mock(GoalResponse.class));
            lenient().when(followupService.maybeBuildFollowup(any(), any()))
                    .thenReturn(Optional.of("Continue toward the goal. Take the next concrete step."));
        }

        GoalEvaluationNode node() {
            return new GoalEvaluationNode(evaluationService, followupService, goalService,
                    properties, windowManager, conversationService, GraphFlavor.REACT);
        }

        /**
         * Build a mocked graph state for an active-goal terminal turn.
         *
         * @param finishReason          the REACT finishReason under test
         * @param followupCount         goal_followup_count already this run
         * @param hardContinuationCount goal_hard_continuation_count already this run
         */
        OverAllState state(String finishReason, int followupCount, int hardContinuationCount) {
            GoalEntity goal = new GoalEntity();
            goal.setId(1L);
            goal.setTitle("ship the feature");

            Map<String, Object> vals = new HashMap<>();
            vals.put(NewsClawStateKeys.ACTIVE_GOAL, goal);
            vals.put(NewsClawStateKeys.GOAL_EVALUATED_THIS_RUN, false);
            vals.put(NewsClawStateKeys.FINISH_REASON, finishReason);
            vals.put(NewsClawStateKeys.AWAITING_APPROVAL, false);
            vals.put(NewsClawStateKeys.FINAL_ANSWER, "partial answer so far");
            vals.put(NewsClawStateKeys.LLM_CALL_COUNT, 10);
            vals.put(NewsClawStateKeys.GOAL_ACCOUNTED_LLM_CALL_COUNT, 0);
            vals.put(NewsClawStateKeys.GOAL_FOLLOWUP_COUNT, followupCount);
            vals.put(NewsClawStateKeys.GOAL_HARD_CONTINUATION_COUNT, hardContinuationCount);
            return new OverAllState(vals);
        }
    }
}

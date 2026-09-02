package vip.newsclaw.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.newsclaw.workflow.model.WorkflowRunEntity;
import vip.newsclaw.workflow.model.WorkflowRunPauseEntity;
import vip.newsclaw.workflow.model.WorkflowRunStepEntity;
import vip.newsclaw.workflow.repository.WorkflowRunMapper;
import vip.newsclaw.workflow.repository.WorkflowRunPauseMapper;
import vip.newsclaw.workflow.repository.WorkflowRunStepMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Regression coverage for the database fence in {@link WorkflowResumer}. */
class WorkflowResumerCasTest {

    @Test
    void losingResumeClaimDoesNotTouchStepRunOrTail() {
        WorkflowRunMapper runs = mock(WorkflowRunMapper.class);
        WorkflowRunPauseMapper pauses = mock(WorkflowRunPauseMapper.class);
        WorkflowRunStepMapper steps = mock(WorkflowRunStepMapper.class);
        WorkflowRunner runner = mock(WorkflowRunner.class);
        PayloadStore payloadStore = mock(PayloadStore.class);

        WorkflowRunPauseEntity pause = new WorkflowRunPauseEntity();
        pause.setId(10L);
        pause.setRunId(20L);
        pause.setStepId(30L);
        pause.setPauseToken("pause-token");
        when(pauses.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pause);

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setId(20L);
        run.setWorkspaceId(7L);
        run.setWorkflowId(8L);
        run.setRevisionId(9L);
        when(runs.selectById(20L)).thenReturn(run);

        WorkflowRunStepEntity step = new WorkflowRunStepEntity();
        step.setId(30L);
        step.setStepIndex(1);
        step.setStepName("approval");
        when(steps.selectById(30L)).thenReturn(step);

        // A concurrent callback already won the SQL claim.
        when(pauses.claimResume(any(), any(), any(), any(), any())).thenReturn(0);

        WorkflowResumer resumer = new WorkflowResumer(
                runs, steps, pauses, runner, payloadStore, new ObjectMapper());

        WorkflowResumer.Outcome result = resumer.resume(
                null, "pause-token", WorkflowResumer.ResumeOutcome.APPROVED, null);

        assertEquals(WorkflowResumer.Outcome.Kind.ALREADY_RESOLVED, result.kind());
        assertEquals(20L, result.runId());
        verify(steps, never()).updateById(any(WorkflowRunStepEntity.class));
        verify(runs, never()).updateById(any(WorkflowRunEntity.class));
        verify(runner, never()).continueFromIndex(any(), any(), any(), any(int.class), any());
    }
}

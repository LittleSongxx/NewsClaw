package vip.newsclaw.workflow.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.newsclaw.workflow.model.WorkflowRunPauseEntity;

import java.time.LocalDateTime;

@Mapper
public interface WorkflowRunPauseMapper extends BaseMapper<WorkflowRunPauseEntity> {

    /**
     * Atomically claim an open pause for resumption.  The pause token is the
     * external idempotency key; the {@code resumed_at IS NULL} predicate is the
     * fencing condition that makes concurrent approval/timeout callbacks
     * mutually exclusive.  Callers must check the affected-row count before
     * executing any post-resume workflow steps.
     */
    @Update("UPDATE mate_workflow_run_pause "
            + "SET resumed_at = #{resumedAt}, "
            + "resume_outcome = #{resumeOutcome}, "
            + "resume_payload_ref = #{resumePayloadRef} "
            + "WHERE id = #{pauseId} "
            + "AND pause_token = #{pauseToken} "
            + "AND resumed_at IS NULL")
    int claimResume(@Param("pauseId") Long pauseId,
                    @Param("pauseToken") String pauseToken,
                    @Param("resumedAt") LocalDateTime resumedAt,
                    @Param("resumeOutcome") String resumeOutcome,
                    @Param("resumePayloadRef") String resumePayloadRef);
}

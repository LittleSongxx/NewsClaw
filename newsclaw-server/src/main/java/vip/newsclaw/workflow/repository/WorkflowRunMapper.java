package vip.newsclaw.workflow.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import vip.newsclaw.workflow.model.WorkflowRunEntity;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRunEntity> {

    @Update("UPDATE mate_workflow_run SET state = 'running', error_message = NULL "
            + "WHERE id = #{runId} AND state = 'paused'")
    int resumePaused(@Param("runId") Long runId);

    @Update("UPDATE mate_workflow_run SET state = 'failed', error_message = #{error}, "
            + "completed_at = #{completedAt} WHERE id = #{runId} AND state = 'paused'")
    int failPaused(@Param("runId") Long runId, @Param("error") String error,
                   @Param("completedAt") LocalDateTime completedAt);

    @Update("UPDATE mate_workflow_run SET state = #{state}, final_output_ref = #{outputRef}, "
            + "error_message = #{error}, completed_at = #{completedAt} "
            + "WHERE id = #{runId} AND state = 'running'")
    int finishRunning(@Param("runId") Long runId, @Param("state") String state,
                      @Param("outputRef") String outputRef, @Param("error") String error,
                      @Param("completedAt") LocalDateTime completedAt);

    @Update("UPDATE mate_workflow_run SET state = 'paused' "
            + "WHERE id = #{runId} AND state = 'running'")
    int pauseRunning(@Param("runId") Long runId);

    /** Bounded recovery scan for runs left in running after a JVM crash. */
    @Select("SELECT * FROM mate_workflow_run "
            + "WHERE state = 'running' AND started_at IS NOT NULL "
            + "AND started_at < #{cutoff} ORDER BY started_at ASC LIMIT 100")
    List<WorkflowRunEntity> selectStaleRunning(@Param("cutoff") LocalDateTime cutoff);

    /** Fail only if the row is still running; a live worker wins the race. */
    @Update("UPDATE mate_workflow_run SET state = 'failed', error_message = #{error}, "
            + "completed_at = #{completedAt} WHERE id = #{runId} AND state = 'running'")
    int failStaleRunning(@Param("runId") Long runId,
                         @Param("error") String error,
                         @Param("completedAt") LocalDateTime completedAt);
}

package vip.newsclaw.workflow.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.newsclaw.workflow.model.WorkflowRunStepEntity;

import java.time.LocalDateTime;

@Mapper
public interface WorkflowRunStepMapper extends BaseMapper<WorkflowRunStepEntity> {

    @Update("UPDATE mate_workflow_run_step SET state = #{state}, output_summary = #{summary}, "
            + "error_message = #{error}, completed_at = #{completedAt} "
            + "WHERE id = #{stepId} AND state = 'paused'")
    int settlePaused(@Param("stepId") Long stepId, @Param("state") String state,
                     @Param("summary") String summary, @Param("error") String error,
                     @Param("completedAt") LocalDateTime completedAt);

    @Update("UPDATE mate_workflow_run_step SET state = #{state}, output_ref = #{outputRef}, "
            + "output_content_type = #{contentType}, output_summary = #{summary}, "
            + "error_message = #{error}, duration_ms = #{durationMs}, completed_at = #{completedAt} "
            + "WHERE id = #{stepId} AND state = 'running'")
    int closeRunning(@Param("stepId") Long stepId, @Param("state") String state,
                     @Param("outputRef") String outputRef, @Param("contentType") String contentType,
                     @Param("summary") String summary, @Param("error") String error,
                     @Param("durationMs") Long durationMs,
                     @Param("completedAt") LocalDateTime completedAt);
}

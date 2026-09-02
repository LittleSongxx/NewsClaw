package vip.newsclaw.memory.fact.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.newsclaw.memory.fact.model.FactContradictionEntity;

import java.time.LocalDateTime;

@Mapper
public interface FactContradictionMapper extends BaseMapper<FactContradictionEntity> {

    /** Resolve once; concurrent decisions must not overwrite the winner. */
    @Update("UPDATE mate_fact_contradiction SET resolution = #{resolution}, "
            + "resolved_at = #{resolvedAt}, resolved_by = #{resolvedBy}, "
            + "update_time = #{updateTime} "
            + "WHERE id = #{contradictionId} AND agent_id = #{agentId} "
            + "AND resolution IS NULL AND deleted = 0")
    int resolveIfOpen(@Param("contradictionId") Long contradictionId,
                      @Param("agentId") Long agentId,
                      @Param("resolution") String resolution,
                      @Param("resolvedAt") LocalDateTime resolvedAt,
                      @Param("resolvedBy") String resolvedBy,
                      @Param("updateTime") LocalDateTime updateTime);
}

package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.news.model.AiNewsScanRunEntity;

@Mapper
public interface AiNewsScanRunMapper extends BaseMapper<AiNewsScanRunEntity> {

    /**
     * Serialize lifecycle decisions which may create downstream records from
     * this run (for example candidate promotion).  The workspace predicate is
     * part of the lock lookup so a caller can never lock/read a run belonging
     * to another tenant and then make a decision against it.
     */
    @Select("SELECT * FROM mate_ai_news_scan_run "
            + "WHERE id = #{scanRunId} AND workspace_id = #{workspaceId} "
            + "AND deleted = 0 FOR UPDATE")
    AiNewsScanRunEntity selectForUpdate(@Param("scanRunId") Long scanRunId,
                                        @Param("workspaceId") Long workspaceId);

    @Select("SELECT summary_json FROM mate_ai_news_scan_run WHERE id = #{id} AND deleted = 0")
    String selectSummaryJson(@Param("id") Long id);
}

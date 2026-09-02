package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import vip.newsclaw.news.model.AiNewsEventClusterEntity;

@Mapper
public interface AiNewsEventClusterMapper extends BaseMapper<AiNewsEventClusterEntity> {

    @Select("""
            SELECT * FROM mate_ai_news_event_cluster
            WHERE id = #{id} AND workspace_id = #{workspaceId} AND deleted = 0
            FOR UPDATE
            """)
    AiNewsEventClusterEntity selectForUpdate(@Param("workspaceId") long workspaceId,
                                             @Param("id") long id);

    @Update("""
            UPDATE mate_ai_news_event_cluster
            SET current_version_id = #{newVersionId}, update_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND workspace_id = #{workspaceId} AND deleted = 0
              AND ((#{expectedVersionId} IS NULL AND current_version_id IS NULL)
                   OR current_version_id = #{expectedVersionId})
            """)
    int compareAndSetVersion(@Param("workspaceId") long workspaceId,
                             @Param("id") long id,
                             @Param("expectedVersionId") Long expectedVersionId,
                             @Param("newVersionId") long newVersionId);
}

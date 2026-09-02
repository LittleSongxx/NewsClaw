package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.news.model.AiNewsEventClusterReviewEntity;

@Mapper
public interface AiNewsEventClusterReviewMapper extends BaseMapper<AiNewsEventClusterReviewEntity> {

    @Select("""
            SELECT * FROM mate_ai_news_event_cluster_review
            WHERE id = #{id} AND workspace_id = #{workspaceId} AND deleted = 0
            FOR UPDATE
            """)
    AiNewsEventClusterReviewEntity selectForUpdate(@Param("workspaceId") long workspaceId,
                                                    @Param("id") long id);
}

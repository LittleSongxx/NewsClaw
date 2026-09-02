package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.news.model.AiNewsReviewTaskEntity;

@Mapper
public interface AiNewsReviewTaskMapper extends BaseMapper<AiNewsReviewTaskEntity> {

    @Select("SELECT * FROM mate_ai_news_review_task "
            + "WHERE workspace_id = #{workspaceId} AND event_id = #{eventId} "
            + "AND deleted = 0 FOR UPDATE")
    AiNewsReviewTaskEntity selectForUpdate(@Param("workspaceId") Long workspaceId,
                                           @Param("eventId") Long eventId);
}

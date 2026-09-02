package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.news.model.AiNewsEventEntity;

@Mapper
public interface AiNewsEventMapper extends BaseMapper<AiNewsEventEntity> {

    /** Lock a workspace event before appending evidence or changing lifecycle state. */
    @Select("""
            SELECT * FROM mate_ai_news_event
             WHERE id = #{eventId} AND workspace_id = #{workspaceId} AND deleted = 0
             FOR UPDATE
            """)
    AiNewsEventEntity selectForUpdate(@Param("workspaceId") Long workspaceId,
                                      @Param("eventId") Long eventId);
}

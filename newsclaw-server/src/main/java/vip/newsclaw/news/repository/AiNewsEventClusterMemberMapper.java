package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.news.model.AiNewsEventClusterMemberEntity;

import java.util.List;

@Mapper
public interface AiNewsEventClusterMemberMapper extends BaseMapper<AiNewsEventClusterMemberEntity> {

    @Select("""
            SELECT member.*
            FROM mate_ai_news_event_cluster_member member
            JOIN mate_ai_news_event_cluster cluster
              ON cluster.id = member.cluster_id
             AND cluster.current_version_id = member.cluster_version_id
             AND cluster.workspace_id = member.workspace_id
             AND cluster.deleted = 0
             AND cluster.status = 'active'
            WHERE member.workspace_id = #{workspaceId}
              AND member.event_id = #{eventId}
              AND member.deleted = 0
            ORDER BY member.cluster_id
            """)
    List<AiNewsEventClusterMemberEntity> selectCurrentMemberships(
            @Param("workspaceId") long workspaceId,
            @Param("eventId") long eventId);

}

package vip.newsclaw.team.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.team.model.AgentTeamMemberEntity;

/**
 * Team membership mapper.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface AgentTeamMemberMapper extends BaseMapper<AgentTeamMemberEntity> {

    /** Serializes task claims for one concrete team member across DB nodes. */
    @Select("SELECT * FROM mate_agent_team_member "
            + "WHERE team_id = #{teamId} AND agent_id = #{agentId} FOR UPDATE")
    AgentTeamMemberEntity lockMember(@Param("teamId") Long teamId,
                                     @Param("agentId") Long agentId);
}

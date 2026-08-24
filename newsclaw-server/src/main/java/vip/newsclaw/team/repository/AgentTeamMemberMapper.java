package vip.newsclaw.team.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.team.model.AgentTeamMemberEntity;

/**
 * Team membership mapper.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface AgentTeamMemberMapper extends BaseMapper<AgentTeamMemberEntity> {
}

package vip.newsclaw.team.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.team.model.AgentTeamEntity;

/**
 * Agent team mapper.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface AgentTeamMapper extends BaseMapper<AgentTeamEntity> {
}

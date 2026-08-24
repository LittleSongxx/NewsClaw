package vip.newsclaw.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.agent.model.AgentEntity;

/**
 * Agent 数据访问层
 *
 * @author NewsClaw Team
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
}

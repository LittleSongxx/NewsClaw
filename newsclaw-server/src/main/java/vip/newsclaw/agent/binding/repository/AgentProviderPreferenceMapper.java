package vip.newsclaw.agent.binding.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.agent.binding.model.AgentProviderPreference;

@Mapper
public interface AgentProviderPreferenceMapper extends BaseMapper<AgentProviderPreference> {
}

package vip.newsclaw.skill.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.skill.usage.SkillUsageStatEntity;

@Mapper
public interface SkillUsageStatMapper extends BaseMapper<SkillUsageStatEntity> {
}

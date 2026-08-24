package vip.newsclaw.skill.routine.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.skill.routine.model.SkillRoutineCandidateEntity;

/**
 * Data access for recurring-request candidates.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface SkillRoutineCandidateMapper extends BaseMapper<SkillRoutineCandidateEntity> {
}

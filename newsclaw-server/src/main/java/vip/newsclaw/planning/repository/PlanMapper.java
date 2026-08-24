package vip.newsclaw.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.planning.model.PlanEntity;

/**
 * 计划 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface PlanMapper extends BaseMapper<PlanEntity> {
}

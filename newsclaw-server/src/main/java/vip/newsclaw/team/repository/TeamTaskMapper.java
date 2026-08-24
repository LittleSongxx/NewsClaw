package vip.newsclaw.team.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.team.model.TeamTaskEntity;

/**
 * Team task board mapper.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface TeamTaskMapper extends BaseMapper<TeamTaskEntity> {
}

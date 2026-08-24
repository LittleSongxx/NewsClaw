package vip.newsclaw.team.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.team.model.TeamTaskCommentEntity;

/**
 * Team task comment mapper.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface TeamTaskCommentMapper extends BaseMapper<TeamTaskCommentEntity> {
}

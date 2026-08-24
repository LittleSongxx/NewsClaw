package vip.newsclaw.workspace.core.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.workspace.core.model.WorkspaceEntity;

/**
 * 工作区 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {
}

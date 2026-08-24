package vip.newsclaw.workspace.document.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.workspace.document.model.WorkspaceFileEntity;

/**
 * 工作区文件 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface WorkspaceFileMapper extends BaseMapper<WorkspaceFileEntity> {
}

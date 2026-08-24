package vip.newsclaw.tool.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.tool.model.ToolEntity;

/**
 * 工具 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface ToolMapper extends BaseMapper<ToolEntity> {
}

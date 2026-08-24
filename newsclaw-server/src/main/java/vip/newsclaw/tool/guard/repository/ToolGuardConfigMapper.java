package vip.newsclaw.tool.guard.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.tool.guard.model.ToolGuardConfigEntity;

@Mapper
public interface ToolGuardConfigMapper extends BaseMapper<ToolGuardConfigEntity> {
}

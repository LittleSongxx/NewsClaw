package vip.mate.tool.guard.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.tool.guard.model.ToolGuardAuditLogEntity;

@Mapper
public interface ToolGuardAuditLogMapper extends BaseMapper<ToolGuardAuditLogEntity> {
}

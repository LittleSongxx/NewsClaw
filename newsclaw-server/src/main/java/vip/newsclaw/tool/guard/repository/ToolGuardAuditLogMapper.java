package vip.newsclaw.tool.guard.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.tool.guard.model.ToolGuardAuditLogEntity;

@Mapper
public interface ToolGuardAuditLogMapper extends BaseMapper<ToolGuardAuditLogEntity> {
}

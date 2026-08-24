package vip.newsclaw.audit.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.audit.model.AuditEventEntity;

@Mapper
public interface AuditEventMapper extends BaseMapper<AuditEventEntity> {
}

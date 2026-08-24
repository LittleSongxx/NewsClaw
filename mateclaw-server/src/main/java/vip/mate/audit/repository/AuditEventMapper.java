package vip.mate.audit.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.audit.model.AuditEventEntity;

@Mapper
public interface AuditEventMapper extends BaseMapper<AuditEventEntity> {
}

package vip.newsclaw.trigger.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.trigger.model.TriggerEventEntity;

@Mapper
public interface TriggerEventMapper extends BaseMapper<TriggerEventEntity> {
}

package vip.mate.system.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.system.model.SystemSettingEntity;

@Mapper
public interface SystemSettingMapper extends BaseMapper<SystemSettingEntity> {
}

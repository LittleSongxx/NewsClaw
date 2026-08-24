package vip.newsclaw.system.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.system.model.SystemSettingEntity;

@Mapper
public interface SystemSettingMapper extends BaseMapper<SystemSettingEntity> {
}

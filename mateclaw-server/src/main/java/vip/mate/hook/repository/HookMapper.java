package vip.mate.hook.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.hook.model.HookEntity;

@Mapper
public interface HookMapper extends BaseMapper<HookEntity> {
}

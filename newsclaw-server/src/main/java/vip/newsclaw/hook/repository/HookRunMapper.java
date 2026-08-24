package vip.newsclaw.hook.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.hook.model.HookRunEntity;

@Mapper
public interface HookRunMapper extends BaseMapper<HookRunEntity> {
}

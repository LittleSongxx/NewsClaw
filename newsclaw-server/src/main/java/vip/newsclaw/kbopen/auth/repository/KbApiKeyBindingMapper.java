package vip.newsclaw.kbopen.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.kbopen.auth.model.KbApiKeyBindingEntity;

@Mapper
public interface KbApiKeyBindingMapper extends BaseMapper<KbApiKeyBindingEntity> {
}

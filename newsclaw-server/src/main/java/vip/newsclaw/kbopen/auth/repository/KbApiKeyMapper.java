package vip.newsclaw.kbopen.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.kbopen.auth.model.KbApiKeyEntity;

@Mapper
public interface KbApiKeyMapper extends BaseMapper<KbApiKeyEntity> {
}

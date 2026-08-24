package vip.newsclaw.auth.sso.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.auth.sso.model.ExternalIdentityEntity;

/**
 * 用户外部身份关联 Mapper。
 *
 * @author NewsClaw Team
 */
@Mapper
public interface ExternalIdentityMapper extends BaseMapper<ExternalIdentityEntity> {
}

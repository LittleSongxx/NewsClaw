package vip.newsclaw.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.auth.model.UserEntity;

/**
 * 用户 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}

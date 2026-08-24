package vip.newsclaw.channel.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.channel.model.ChannelSessionEntity;

/**
 * 渠道会话 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface ChannelSessionMapper extends BaseMapper<ChannelSessionEntity> {
}

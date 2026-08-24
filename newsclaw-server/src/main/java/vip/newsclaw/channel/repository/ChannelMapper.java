package vip.newsclaw.channel.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.channel.model.ChannelEntity;

/**
 * 渠道 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface ChannelMapper extends BaseMapper<ChannelEntity> {
}

package vip.newsclaw.plugin.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.plugin.model.PluginEntity;

/**
 * MyBatis Plus mapper for mate_plugin table.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface PluginMapper extends BaseMapper<PluginEntity> {
}

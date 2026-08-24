package vip.newsclaw.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.memory.model.MemoryRecallEntity;

/**
 * 记忆召回追踪 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface MemoryRecallMapper extends BaseMapper<MemoryRecallEntity> {
}

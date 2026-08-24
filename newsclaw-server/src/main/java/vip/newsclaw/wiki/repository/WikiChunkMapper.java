package vip.newsclaw.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.wiki.model.WikiChunkEntity;

/**
 * Wiki chunk 数据访问层
 *
 * @author NewsClaw Team
 */
@Mapper
public interface WikiChunkMapper extends BaseMapper<WikiChunkEntity> {
}

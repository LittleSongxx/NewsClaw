package vip.newsclaw.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.wiki.model.WikiPipelineRunEntity;

/**
 * Mapper for {@link WikiPipelineRunEntity}.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface WikiPipelineRunMapper extends BaseMapper<WikiPipelineRunEntity> {
}

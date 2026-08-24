package vip.newsclaw.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.wiki.model.WikiPipelineStepRunEntity;

/**
 * Mapper for {@link WikiPipelineStepRunEntity}.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface WikiPipelineStepRunMapper extends BaseMapper<WikiPipelineStepRunEntity> {
}

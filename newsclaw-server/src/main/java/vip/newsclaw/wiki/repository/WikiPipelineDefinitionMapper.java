package vip.newsclaw.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.wiki.model.WikiPipelineDefinitionEntity;

/**
 * Mapper for {@link WikiPipelineDefinitionEntity}.
 *
 * @author NewsClaw Team
 */
@Mapper
public interface WikiPipelineDefinitionMapper extends BaseMapper<WikiPipelineDefinitionEntity> {
}

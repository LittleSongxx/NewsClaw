package vip.newsclaw.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;

/**
 * Wiki 知识库 Mapper
 *
 * @author NewsClaw Team
 */
@Mapper
public interface WikiKnowledgeBaseMapper extends BaseMapper<WikiKnowledgeBaseEntity> {
}

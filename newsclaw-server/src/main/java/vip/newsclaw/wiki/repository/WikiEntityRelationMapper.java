package vip.newsclaw.wiki.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.newsclaw.wiki.model.WikiEntityRelationEntity;

/**
 * Mapper for entity-to-entity fact triples.
 *
 * <p>Read paths traverse the ego-graph by {@code subject_entity_id} /
 * {@code object_entity_id}; write paths upsert keyed by the triple
 * ({@code kb_id}, {@code subject_entity_id}, {@code predicate},
 * {@code object_entity_id}).
 *
 * @author NewsClaw Team
 */
@Mapper
public interface WikiEntityRelationMapper extends BaseMapper<WikiEntityRelationEntity> {
}

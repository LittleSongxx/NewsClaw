package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.news.model.AiNewsIngestionRunItemEntity;
import vip.newsclaw.news.model.AiNewsRunItemObservationRow;

import java.util.List;

@Mapper
public interface AiNewsIngestionRunItemMapper extends BaseMapper<AiNewsIngestionRunItemEntity> {

    @Select("""
            SELECT ri.id AS observationId,
                   ri.source_item_id AS sourceItemId,
                   ri.source_item_version_id AS sourceItemVersionId,
                   ri.observation_outcome AS observationOutcome,
                   ri.observed_at AS observedAt,
                   i.external_item_id AS externalItemId,
                   i.canonical_url AS canonicalUrl,
                   i.source_url AS sourceUrl,
                   i.source_tier AS sourceTier,
                   v.version_hash AS versionHash,
                   v.title AS title,
                   v.source_published_at AS sourcePublishedAt
              FROM mate_ai_news_ingestion_run_item ri
              JOIN mate_ai_news_source_item i ON i.id = ri.source_item_id
              JOIN mate_ai_news_source_item_version v ON v.id = ri.source_item_version_id
             WHERE ri.ingestion_run_id = #{runId}
               AND ri.deleted = 0 AND i.deleted = 0 AND v.deleted = 0
             ORDER BY ri.id ASC
             LIMIT #{limit}
            """)
    List<AiNewsRunItemObservationRow> selectRunObservations(
            @Param("runId") Long runId, @Param("limit") int limit);
}

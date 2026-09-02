package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.news.model.AiNewsIngestedCandidateRow;
import vip.newsclaw.news.model.AiNewsSourceItemVersionEntity;
import vip.newsclaw.news.model.AiNewsSourceTimeAttestationRow;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiNewsSourceItemVersionMapper extends BaseMapper<AiNewsSourceItemVersionEntity> {

    @Select("""
            SELECT v.title AS title,
                   v.snippet AS snippet,
                   v.content AS content,
                   v.provenance_json AS provenanceJson,
                   i.source_url AS sourceUrl,
                   i.canonical_url AS canonicalUrl,
                   i.source_tier AS sourceTier,
                   e.provider_id AS providerId,
                   i.first_observed_at AS firstObservedAt,
                   i.last_observed_at AS lastObservedAt
              FROM mate_ai_news_source_item_version v
              JOIN mate_ai_news_source_item i ON i.latest_version_id = v.id
              JOIN mate_ai_news_source_endpoint e ON e.id = i.endpoint_id
             WHERE v.deleted = 0 AND i.deleted = 0 AND e.deleted = 0 AND e.enabled = TRUE
               AND (v.source_published_at >= #{since}
                    OR (v.source_published_at IS NULL AND i.first_observed_at >= #{since}))
             ORDER BY COALESCE(v.source_published_at, i.first_observed_at) DESC, i.id ASC
             LIMIT #{limit}
            """)
    List<AiNewsIngestedCandidateRow> selectRecentLatest(
            @Param("since") LocalDateTime since,
            @Param("limit") int limit);

    /** Latest versions whose canonical article URL exactly matches a capture target. */
    @Select("""
            SELECT i.id AS sourceItemId,
                   v.id AS sourceItemVersionId,
                   v.ingestion_run_id AS ingestionRunId,
                   v.version_hash AS versionHash,
                   i.canonical_url AS canonicalUrl,
                   i.source_url AS sourceUrl,
                   i.source_tier AS sourceTier,
                   v.source_published_at AS sourcePublishedAt,
                   v.published_at_raw AS publishedAtRaw,
                   v.provenance_json AS provenanceJson,
                   v.observed_at AS observedAt,
                   e.id AS endpointId,
                   e.endpoint_key AS endpointKey,
                   e.catalog_version AS catalogVersion,
                   e.source_key AS endpointSourceKey,
                   e.provider_id AS providerId,
                   e.adapter AS adapter,
                   e.endpoint_url AS endpointUrl,
                   e.enabled AS endpointEnabled,
                   e.evidence_eligible AS evidenceEligible,
                   e.rights_status AS rightsStatus,
                   e.robots_status AS robotsStatus,
                   r.run_status AS runStatus
              FROM mate_ai_news_source_item_version v
              JOIN mate_ai_news_source_item i ON i.latest_version_id = v.id
              JOIN mate_ai_news_source_endpoint e ON e.id = i.endpoint_id
              JOIN mate_ai_news_ingestion_run r ON r.id = v.ingestion_run_id
             WHERE i.canonical_url_hash = #{canonicalUrlHash}
               AND v.deleted = 0 AND i.deleted = 0 AND e.deleted = 0 AND r.deleted = 0
             ORDER BY v.observed_at DESC, v.id DESC
             LIMIT #{limit}
            """)
    List<AiNewsSourceTimeAttestationRow> selectLatestTimeAttestations(
            @Param("canonicalUrlHash") String canonicalUrlHash,
            @Param("limit") int limit);

    /** Immutable version lookup used to revalidate a structured-time-bound capture. */
    @Select("""
            SELECT i.id AS sourceItemId,
                   v.id AS sourceItemVersionId,
                   v.ingestion_run_id AS ingestionRunId,
                   v.version_hash AS versionHash,
                   i.canonical_url AS canonicalUrl,
                   i.source_url AS sourceUrl,
                   i.source_tier AS sourceTier,
                   v.source_published_at AS sourcePublishedAt,
                   v.published_at_raw AS publishedAtRaw,
                   v.provenance_json AS provenanceJson,
                   v.observed_at AS observedAt,
                   e.id AS endpointId,
                   e.endpoint_key AS endpointKey,
                   e.catalog_version AS catalogVersion,
                   e.source_key AS endpointSourceKey,
                   e.provider_id AS providerId,
                   e.adapter AS adapter,
                   e.endpoint_url AS endpointUrl,
                   e.enabled AS endpointEnabled,
                   e.evidence_eligible AS evidenceEligible,
                   e.rights_status AS rightsStatus,
                   e.robots_status AS robotsStatus,
                   r.run_status AS runStatus
              FROM mate_ai_news_source_item_version v
              JOIN mate_ai_news_source_item i ON i.id = v.source_item_id
              JOIN mate_ai_news_source_endpoint e ON e.id = i.endpoint_id
              JOIN mate_ai_news_ingestion_run r ON r.id = v.ingestion_run_id
             WHERE v.id = #{versionId}
               AND v.deleted = 0 AND i.deleted = 0 AND e.deleted = 0 AND r.deleted = 0
            """)
    AiNewsSourceTimeAttestationRow selectTimeAttestationByVersionId(
            @Param("versionId") Long versionId);
}

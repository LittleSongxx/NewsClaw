package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.newsclaw.news.model.AiNewsRawCaptureEntity;
import vip.newsclaw.news.model.AiNewsRawCaptureMetadataRow;

import java.util.List;

@Mapper
public interface AiNewsRawCaptureMapper extends BaseMapper<AiNewsRawCaptureEntity> {

    @Select("""
            SELECT id AS id,
                   endpoint_id AS endpointId,
                   request_url AS requestUrl,
                   request_url_hash AS requestUrlHash,
                   attempt_no AS attemptNo,
                   final_url AS finalUrl,
                   http_status AS httpStatus,
                   content_type AS contentType,
                   etag AS etag,
                   last_modified AS lastModified,
                   retry_after AS retryAfter,
                   declared_content_length AS declaredContentLength,
                   received_bytes AS receivedBytes,
                   representation_digest AS representationDigest,
                   retention_applied AS retentionApplied,
                   CASE WHEN raw_body IS NOT NULL OR body_object_key IS NOT NULL
                        THEN TRUE ELSE FALSE END AS bodyRetained,
                   truncated AS truncated,
                   not_modified AS notModified,
                   started_at AS startedAt,
                   finished_at AS finishedAt,
                   duration_ms AS durationMs,
                   error_code AS errorCode,
                   error_message AS errorMessage,
                   revalidated_from_capture_id AS revalidatedFromCaptureId
              FROM mate_ai_news_raw_capture
             WHERE ingestion_run_id = #{runId} AND deleted = 0
             ORDER BY started_at ASC, attempt_no ASC, id ASC
             LIMIT #{limit}
            """)
    List<AiNewsRawCaptureMetadataRow> selectRunMetadata(
            @Param("runId") Long runId, @Param("limit") int limit);
}

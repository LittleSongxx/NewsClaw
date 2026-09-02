package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import vip.newsclaw.news.model.AiNewsCandidateEntity;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiNewsCandidateMapper extends BaseMapper<AiNewsCandidateEntity> {

    /** Serialize review/promotion decisions for one candidate row. */
    @Select("SELECT * FROM mate_ai_news_candidate "
            + "WHERE id = #{candidateId} AND workspace_id = #{workspaceId} "
            + "AND deleted = 0 FOR UPDATE")
    AiNewsCandidateEntity selectForUpdate(@Param("candidateId") Long candidateId,
                                           @Param("workspaceId") Long workspaceId);

    /** Update only review columns; never write a stale candidate projection back. */
    @Update("UPDATE mate_ai_news_candidate "
            + "SET review_status = #{decision}, review_reason = #{reason}, "
            + "reviewed_by = #{reviewedBy}, reviewed_at = #{reviewedAt}, "
            + "review_origin = #{reviewOrigin}, update_time = #{now} "
            + "WHERE id = #{candidateId} AND workspace_id = #{workspaceId} AND deleted = 0 "
            + "AND event_id IS NULL")
    int updateReview(@Param("candidateId") Long candidateId,
                     @Param("workspaceId") Long workspaceId,
                     @Param("decision") String decision,
                     @Param("reason") String reason,
                     @Param("reviewedBy") String reviewedBy,
                     @Param("reviewedAt") LocalDateTime reviewedAt,
                     @Param("reviewOrigin") String reviewOrigin,
                     @Param("now") LocalDateTime now);

    /**
     * Refresh only discovery-owned columns.  Promotion/capture/review state
     * is intentionally excluded: a stale discovery projection must never
     * write an old {@code event_id}, capture lease, or human decision back
     * over a newer state transition.  The event fence also makes a late
     * discovery replay a harmless no-op after promotion.
     */
    @Update("""
            UPDATE mate_ai_news_candidate
               SET original_url = #{candidate.originalUrl},
                   title = #{candidate.title},
                   snippet = #{candidate.snippet},
                   provider_id = #{candidate.providerId},
                   query_lane = #{candidate.queryLane},
                   provider_rank = #{candidate.providerRank},
                   source_key = #{candidate.sourceKey},
                   source_class = #{candidate.sourceClass},
                   published_at_hint = #{candidate.publishedAtHint},
                   time_confidence = #{candidate.timeConfidence},
                   last_seen_at = #{candidate.lastSeenAt},
                   acquisition_status = #{candidate.acquisitionStatus},
                   selection_status = #{candidate.selectionStatus},
                   selection_score = #{candidate.selectionScore},
                   selection_reason = #{candidate.selectionReason},
                   reject_reason = #{candidate.rejectReason},
                   story_id = #{candidate.storyId},
                   config_version = #{candidate.configVersion},
                   update_time = #{candidate.updateTime}
             WHERE id = #{candidate.id}
               AND workspace_id = #{candidate.workspaceId}
               AND scan_run_id = #{candidate.scanRunId}
               AND event_id IS NULL
               AND deleted = 0
            """)
    int updateDiscovery(@Param("candidate") AiNewsCandidateEntity candidate);

    /** Queue a selected candidate without resetting an in-flight capture. */
    @Update("""
            UPDATE mate_ai_news_candidate
               SET capture_status = 'PENDING',
                   normalization_status = 'NOT_STARTED',
                   next_capture_at = NULL,
                   failure_reason = NULL,
                   update_time = #{now}
             WHERE id = #{candidateId}
               AND workspace_id = #{workspaceId}
               AND scan_run_id = #{scanRunId}
               AND event_id IS NULL
               AND capture_status IN ('NOT_QUEUED', 'PENDING', 'RETRYABLE', 'FAILED')
               AND deleted = 0
            """)
    int queueSelected(@Param("candidateId") Long candidateId,
                      @Param("workspaceId") Long workspaceId,
                      @Param("scanRunId") Long scanRunId,
                      @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT c.* FROM mate_ai_news_candidate c
             WHERE 1 = 1
            <if test="scanRunId != null">
               AND c.scan_run_id = #{scanRunId}
               AND c.id IN (
                   SELECT o.candidate_id FROM mate_ai_news_candidate_observation o
                    WHERE o.scan_run_id = #{scanRunId} AND o.selected = TRUE AND o.deleted = 0)
             </if>
             <if test="workspaceId != null">
               AND c.workspace_id = #{workspaceId}
             </if>
               AND c.capture_status IN ('PENDING', 'RETRYABLE')
               AND (c.next_capture_at IS NULL OR c.next_capture_at &lt;= #{now})
               AND c.event_id IS NULL
               AND c.deleted = 0
               AND EXISTS (
                   SELECT 1
                    FROM mate_ai_news_candidate_observation q
                    JOIN mate_ai_news_scan_run r ON r.id = q.scan_run_id
                   WHERE q.candidate_id = c.id
                     AND q.scan_run_id = c.scan_run_id
                      AND q.selected = TRUE
                      AND q.deleted = 0
                      AND r.deleted = 0
                      AND r.run_status IN ('RUNNING', 'CANDIDATES_PERSISTED', 'CAPTURE_PENDING')
                      <if test="scanRunId != null">
                        AND q.scan_run_id = #{scanRunId}
                      </if>
                      <if test="workspaceId != null">
                        AND r.workspace_id = #{workspaceId}
                      </if>
               )
             ORDER BY c.selection_score DESC, c.provider_rank ASC, c.id ASC
             LIMIT #{limit}
            </script>
            """)
    List<AiNewsCandidateEntity> selectCaptureQueue(
            @Param("scanRunId") Long scanRunId,
            @Param("workspaceId") Long workspaceId,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    @Update("""
            UPDATE mate_ai_news_candidate
               SET capture_status = 'CAPTURING',
                   capture_attempts = capture_attempts + 1,
                   capture_started_at = #{now},
                   update_time = #{now}
             WHERE id = #{id}
               AND capture_status IN ('PENDING', 'RETRYABLE')
               AND (next_capture_at IS NULL OR next_capture_at <= #{now})
               AND event_id IS NULL
               AND deleted = 0
               AND EXISTS (
                   SELECT 1
                     FROM mate_ai_news_candidate_observation q
                     JOIN mate_ai_news_scan_run r ON r.id = q.scan_run_id
                    WHERE q.candidate_id = mate_ai_news_candidate.id
                      AND q.scan_run_id = mate_ai_news_candidate.scan_run_id
                      AND q.selected = TRUE
                      AND q.deleted = 0
                      AND r.workspace_id = mate_ai_news_candidate.workspace_id
                      AND r.deleted = 0
                      AND r.run_status IN ('RUNNING', 'CANDIDATES_PERSISTED', 'CAPTURE_PENDING')
               )
            """)
    int claimCapture(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Finish only the attempt that the caller actually claimed.  The attempt
     * number is a cheap fencing token: a stale worker which was recovered and
     * re-claimed by another worker cannot overwrite the newer result.
     */
    @Update("""
            UPDATE mate_ai_news_candidate
               SET capture_status = 'SUCCESS',
                   capture_id = #{captureId},
                   acquisition_status = 'CAPTURED',
                   normalization_status = 'USABLE',
                   capture_started_at = NULL,
                   next_capture_at = NULL,
                   failure_reason = NULL,
                   update_time = #{now}
             WHERE id = #{id}
               AND capture_status = 'CAPTURING'
               AND capture_attempts = #{expectedAttempt}
               AND event_id IS NULL
               AND deleted = 0
               AND EXISTS (
                   SELECT 1
                     FROM mate_ai_news_candidate_observation q
                     JOIN mate_ai_news_scan_run r ON r.id = q.scan_run_id
                    WHERE q.candidate_id = mate_ai_news_candidate.id
                      AND q.scan_run_id = mate_ai_news_candidate.scan_run_id
                      AND q.selected = TRUE
                      AND q.deleted = 0
                      AND r.workspace_id = mate_ai_news_candidate.workspace_id
                      AND r.deleted = 0
                      AND r.run_status IN ('RUNNING', 'CANDIDATES_PERSISTED', 'CAPTURE_PENDING')
               )
            """)
    int completeCaptureSucceeded(@Param("id") Long id,
                                 @Param("expectedAttempt") int expectedAttempt,
                                 @Param("captureId") Long captureId,
                                 @Param("now") LocalDateTime now);

    /** Fenced failure counterpart; status/next time are computed by the service. */
    @Update("""
            UPDATE mate_ai_news_candidate
               SET capture_status = #{status},
                   normalization_status = 'FAILED',
                   capture_started_at = NULL,
                   failure_reason = #{reason},
                   next_capture_at = #{nextCaptureAt},
                   update_time = #{now}
             WHERE id = #{id}
               AND capture_status = 'CAPTURING'
               AND capture_attempts = #{expectedAttempt}
               AND event_id IS NULL
               AND deleted = 0
               AND EXISTS (
                   SELECT 1
                     FROM mate_ai_news_candidate_observation q
                     JOIN mate_ai_news_scan_run r ON r.id = q.scan_run_id
                    WHERE q.candidate_id = mate_ai_news_candidate.id
                      AND q.scan_run_id = mate_ai_news_candidate.scan_run_id
                      AND q.selected = TRUE
                      AND q.deleted = 0
                      AND r.workspace_id = mate_ai_news_candidate.workspace_id
                      AND r.deleted = 0
                      AND r.run_status IN ('RUNNING', 'CANDIDATES_PERSISTED', 'CAPTURE_PENDING')
               )
            """)
    int completeCaptureFailed(@Param("id") Long id,
                              @Param("expectedAttempt") int expectedAttempt,
                              @Param("status") String status,
                              @Param("reason") String reason,
                              @Param("nextCaptureAt") LocalDateTime nextCaptureAt,
                              @Param("now") LocalDateTime now);

    /** Stop every unfinished capture when its owning scan is failed/cancelled. */
    @Update("""
            UPDATE mate_ai_news_candidate
               SET capture_status = 'FAILED',
                   normalization_status = 'FAILED',
                   capture_started_at = NULL,
                   next_capture_at = NULL,
                   failure_reason = #{reason},
                   update_time = #{now}
             WHERE scan_run_id = #{scanRunId}
               AND capture_status IN ('PENDING', 'RETRYABLE', 'CAPTURING')
               AND event_id IS NULL
               AND deleted = 0
            """)
    int failUnfinishedForRun(@Param("scanRunId") Long scanRunId,
                             @Param("reason") String reason,
                             @Param("now") LocalDateTime now);

    /** Link one candidate to its deterministic event exactly once. */
    @Update("""
            UPDATE mate_ai_news_candidate
               SET event_id = #{eventId},
                   promoted_at = #{now},
                   update_time = #{now}
             WHERE id = #{candidateId}
               AND workspace_id = #{workspaceId}
               AND event_id IS NULL
               AND selection_status = 'SELECTED'
               AND review_status = 'ACCEPTED'
               AND capture_status = 'SUCCESS'
               AND capture_id = #{captureId}
               AND deleted = 0
            """)
    int linkPromotedEvent(@Param("candidateId") Long candidateId,
                          @Param("workspaceId") Long workspaceId,
                          @Param("eventId") Long eventId,
                          @Param("captureId") Long captureId,
                          @Param("now") LocalDateTime now);

    @Update("""
            <script>
            UPDATE mate_ai_news_candidate c
               SET capture_status = 'RETRYABLE',
                   failure_reason = 'STALE_CAPTURE_RECOVERED',
                   next_capture_at = NULL,
                   update_time = #{now}
             WHERE c.capture_status = 'CAPTURING'
               AND c.capture_started_at &lt; #{staleBefore}
               AND c.event_id IS NULL
               AND c.deleted = 0
             <if test="workspaceId != null">
               AND c.workspace_id = #{workspaceId}
             </if>
             <if test="scanRunId != null">
               AND c.scan_run_id = #{scanRunId}
               AND EXISTS (
                   SELECT 1 FROM mate_ai_news_candidate_observation o
                    WHERE o.candidate_id = c.id
                      AND o.scan_run_id = #{scanRunId}
                      AND o.selected = TRUE
                       AND o.deleted = 0)
             </if>
             AND EXISTS (
                 SELECT 1
                   FROM mate_ai_news_candidate_observation q
                   JOIN mate_ai_news_scan_run r ON r.id = q.scan_run_id
                  WHERE q.candidate_id = c.id
                    AND q.scan_run_id = c.scan_run_id
                    AND q.selected = TRUE
                    AND q.deleted = 0
                    AND r.deleted = 0
                    AND r.run_status IN ('RUNNING', 'CANDIDATES_PERSISTED', 'CAPTURE_PENDING')
                    <if test="scanRunId != null">
                      AND q.scan_run_id = #{scanRunId}
                    </if>
                    <if test="workspaceId != null">
                      AND r.workspace_id = #{workspaceId}
                    </if>
                    AND r.workspace_id = c.workspace_id
             )
            </script>
            """)
    int recoverStaleCaptures(@Param("workspaceId") Long workspaceId,
                             @Param("scanRunId") Long scanRunId,
                             @Param("staleBefore") LocalDateTime staleBefore,
                             @Param("now") LocalDateTime now);

    /** Backward-compatible global maintenance hook; scheduled paths pass a scope. */
    default int recoverStaleCaptures(LocalDateTime staleBefore, LocalDateTime now) {
        return recoverStaleCaptures(null, null, staleBefore, now);
    }
}

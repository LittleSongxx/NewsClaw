package vip.newsclaw.news.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.newsclaw.news.model.AiNewsSourceEndpointEntity;

import java.time.LocalDateTime;

@Mapper
public interface AiNewsSourceEndpointMapper extends BaseMapper<AiNewsSourceEndpointEntity> {

    /** Atomically claim a due endpoint across scheduler and request threads/nodes. */
    @Update("""
            UPDATE mate_ai_news_source_endpoint
               SET next_poll_at = #{leaseUntil},
                   last_attempt_at = #{now},
                   update_time = #{now}
             WHERE id = #{id}
               AND enabled = TRUE
               AND deleted = 0
               AND (next_poll_at IS NULL OR next_poll_at <= #{now})
            """)
    int claimDue(@Param("id") Long id,
                 @Param("now") LocalDateTime now,
                 @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * Release a lease left by a stale run, but only while the endpoint still
     * carries that stale owner's cursor.  A newer claimant changes both
     * timestamps, so its lease cannot be clobbered by reconciliation.
     */
    @Update("""
            UPDATE mate_ai_news_source_endpoint
               SET next_poll_at = #{now},
                   update_time = #{now}
             WHERE id = #{id}
               AND enabled = TRUE
               AND deleted = 0
               AND (next_poll_at IS NULL OR next_poll_at <= #{now})
               AND (last_attempt_at IS NULL OR last_attempt_at <= #{staleBefore})
            """)
    int releaseStaleLease(@Param("id") Long id,
                          @Param("staleBefore") LocalDateTime staleBefore,
                          @Param("now") LocalDateTime now);

    /** Record a failed run only while the endpoint cursor still belongs to it. */
    @Update("""
            UPDATE mate_ai_news_source_endpoint
               SET consecutive_failures = #{failures},
                   last_error = #{error},
                   next_poll_at = #{nextPollAt},
                   update_time = #{now}
             WHERE id = #{id}
               AND enabled = TRUE
               AND deleted = 0
               AND last_attempt_at = #{ownerStartedAt}
            """)
    int recordFailureIfOwned(@Param("id") Long id,
                             @Param("ownerStartedAt") LocalDateTime ownerStartedAt,
                             @Param("failures") int failures,
                             @Param("error") String error,
                             @Param("nextPollAt") LocalDateTime nextPollAt,
                             @Param("now") LocalDateTime now);
}

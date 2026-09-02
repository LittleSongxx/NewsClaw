package vip.newsclaw.trigger.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.newsclaw.trigger.model.TriggerEntity;

import java.time.LocalDateTime;

@Mapper
public interface TriggerMapper extends BaseMapper<TriggerEntity> {

    /** Reserve one fire slot before dispatch; max_fires is fenced in SQL. */
    @Update("""
            UPDATE mate_trigger
               SET fire_count = COALESCE(fire_count, 0) + 1,
                   last_dispatched_at = #{now},
                   update_time = #{now}
             WHERE id = #{triggerId} AND deleted = 0 AND enabled = TRUE
               AND (max_fires IS NULL OR max_fires <= 0
                    OR COALESCE(fire_count, 0) < max_fires)
            """)
    int claimFire(@Param("triggerId") Long triggerId,
                  @Param("now") LocalDateTime now);

    /** Settle a pre-reserved fire without incrementing it a second time. */
    @Update("""
            UPDATE mate_trigger
               SET last_fired_at = CASE WHEN #{fired} = 1 THEN #{now} ELSE last_fired_at END,
                   fire_count = CASE WHEN #{fired} = 1 THEN fire_count
                                     ELSE CASE WHEN COALESCE(fire_count, 0) > 0
                                               THEN fire_count - 1 ELSE 0 END END,
                   last_error = #{lastError,jdbcType=VARCHAR},
                   update_time = #{now}
             WHERE id = #{triggerId} AND deleted = 0
            """)
    int settleClaimedFire(@Param("triggerId") Long triggerId,
                          @Param("fired") int fired,
                          @Param("lastError") String lastError,
                          @Param("now") LocalDateTime now);

    /**
     * Record dispatch bookkeeping without writing a stale trigger snapshot.
     * The scheduler and event-ingest workers can therefore update counters
     * concurrently with an administrator editing the pattern or payload.
     */
    @Update("""
            UPDATE mate_trigger
               SET last_dispatched_at = #{now},
                   last_fired_at = CASE WHEN #{fired} = 1 THEN #{now} ELSE last_fired_at END,
                   fire_count = CASE WHEN #{fired} = 1 THEN COALESCE(fire_count, 0) + 1 ELSE fire_count END,
                   last_error = #{lastError,jdbcType=VARCHAR},
                   update_time = #{now}
             WHERE id = #{triggerId} AND deleted = 0
            """)
    int recordDispatchOutcome(@Param("triggerId") Long triggerId,
                              @Param("fired") int fired,
                              @Param("lastError") String lastError,
                              @Param("now") LocalDateTime now);
}

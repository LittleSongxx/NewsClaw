package vip.newsclaw.trigger.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.newsclaw.trigger.model.TriggerEventEntity;

import java.time.LocalDateTime;

@Mapper
public interface TriggerEventMapper extends BaseMapper<TriggerEventEntity> {

    /** Atomically reclaims a duplicate key after its prior dedup lease expired. */
    @Update("UPDATE mate_trigger_event SET received_at = #{receivedAt}, expires_at = #{expiresAt} "
            + "WHERE trigger_id = #{triggerId} AND dedup_key = #{dedupKey} "
            + "AND expires_at <= #{receivedAt}")
    int reclaimExpired(@Param("triggerId") Long triggerId,
                       @Param("dedupKey") String dedupKey,
                       @Param("receivedAt") LocalDateTime receivedAt,
                       @Param("expiresAt") LocalDateTime expiresAt);
}

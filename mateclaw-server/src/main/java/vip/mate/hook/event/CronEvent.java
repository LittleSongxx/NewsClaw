package vip.mate.hook.event;

import java.time.Instant;
import java.util.Map;

/**
 * 定时任务事件（cron:triggered | cron:completed | cron:failed | cron:skipped）。
 *
 * @param type      事件子类型；必须以 {@code cron:} 开头
 * @param timestamp 发生时间
 * @param jobId     定时任务主键
 * @param jobKey    任务唯一 key
 * @param payload   exitCode / durationMs / nextRunAt 等
 */
public record CronEvent(
        String type,
        Instant timestamp,
        Long jobId,
        String jobKey,
        Map<String, Object> payload) implements MateHookEvent {

    public CronEvent {
        if (type == null || !type.startsWith("cron:")) {
            throw new IllegalArgumentException("CronEvent.type must start with 'cron:' but got: " + type);
        }
        if (timestamp == null) timestamp = Instant.now();
        payload = (payload == null) ? Map.of() : Map.copyOf(payload);
    }

    public static CronEvent of(String action, Long jobId, String jobKey,
                               Map<String, Object> payload) {
        return new CronEvent("cron:" + action, Instant.now(), jobId, jobKey, payload);
    }
}

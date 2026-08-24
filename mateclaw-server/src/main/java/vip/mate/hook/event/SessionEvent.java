package vip.mate.hook.event;

import java.time.Instant;
import java.util.Map;

/**
 * 会话生命周期事件（session:start | session:message | session:end | session:reset）。
 *
 * @param type           事件子类型；必须以 {@code session:} 开头
 * @param timestamp      发生时间
 * @param conversationId 会话主键
 * @param workspaceId    所属 workspace
 * @param payload        额外载荷（messageCount / userId / channelType 等）
 */
public record SessionEvent(
        String type,
        Instant timestamp,
        Long conversationId,
        Long workspaceId,
        Map<String, Object> payload) implements MateHookEvent {

    public SessionEvent {
        if (type == null || !type.startsWith("session:")) {
            throw new IllegalArgumentException("SessionEvent.type must start with 'session:' but got: " + type);
        }
        if (timestamp == null) timestamp = Instant.now();
        payload = (payload == null) ? Map.of() : Map.copyOf(payload);
    }

    public static SessionEvent of(String action, Long conversationId, Long workspaceId,
                                  Map<String, Object> payload) {
        return new SessionEvent("session:" + action, Instant.now(), conversationId, workspaceId, payload);
    }
}

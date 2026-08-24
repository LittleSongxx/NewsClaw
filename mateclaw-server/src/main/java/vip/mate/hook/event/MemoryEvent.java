package vip.mate.hook.event;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆系统事件（memory:written | memory:recalled | memory:consolidated | memory:dreamed）。
 *
 * @param type      事件子类型；必须以 {@code memory:} 开头
 * @param timestamp 发生时间
 * @param provider  记忆提供者名（builtin / structured / session_search 等）
 * @param agentId   相关 agent
 * @param payload   token count / consolidation summary / recall count 等
 */
public record MemoryEvent(
        String type,
        Instant timestamp,
        String provider,
        Long agentId,
        Map<String, Object> payload) implements MateHookEvent {

    public MemoryEvent {
        if (type == null || !type.startsWith("memory:")) {
            throw new IllegalArgumentException("MemoryEvent.type must start with 'memory:' but got: " + type);
        }
        if (timestamp == null) timestamp = Instant.now();
        payload = (payload == null) ? Map.of() : Map.copyOf(payload);
    }

    public static MemoryEvent of(String action, String provider, Long agentId,
                                 Map<String, Object> payload) {
        return new MemoryEvent("memory:" + action, Instant.now(), provider, agentId, payload);
    }
}

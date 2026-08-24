package vip.mate.hook.event;

import java.time.Instant;
import java.util.Map;

/**
 * 工具调用事件（tool:before | tool:after | tool:error | tool:blocked_by_guard | tool:needs_approval）。
 *
 * @param type      事件子类型；必须以 {@code tool:} 开头
 * @param timestamp 发生时间
 * @param toolName  工具名（如 {@code shell} / {@code wiki.search}）
 * @param agentId   触发该工具的 agent
 * @param traceId   本次对话/运行的追踪 ID
 * @param payload   args digest / result size / duration / error 等
 */
public record ToolEvent(
        String type,
        Instant timestamp,
        String toolName,
        Long agentId,
        String traceId,
        Map<String, Object> payload) implements MateHookEvent {

    public ToolEvent {
        if (type == null || !type.startsWith("tool:")) {
            throw new IllegalArgumentException("ToolEvent.type must start with 'tool:' but got: " + type);
        }
        if (timestamp == null) timestamp = Instant.now();
        payload = (payload == null) ? Map.of() : Map.copyOf(payload);
    }

    public static ToolEvent of(String action, String toolName, Long agentId, String traceId,
                               Map<String, Object> payload) {
        return new ToolEvent("tool:" + action, Instant.now(), toolName, agentId, traceId, payload);
    }
}

package vip.mate.hook.event;

import java.time.Instant;
import java.util.Map;

/**
 * Agent 生命周期事件（agent:start | agent:step | agent:end | agent:error）。
 *
 * @param type       事件子类型；必须以 {@code agent:} 开头
 * @param timestamp  发生时间
 * @param agentId    agent 主键
 * @param traceId    本次对话/运行的追踪 ID
 * @param iteration  当前迭代次数（ReAct）或 step 序号（Plan-Execute）
 * @param payload    额外结构化载荷（durationMs / finishReason / toolCallCount 等）
 */
public record AgentEvent(
        String type,
        Instant timestamp,
        Long agentId,
        String traceId,
        int iteration,
        Map<String, Object> payload) implements MateHookEvent {

    public AgentEvent {
        if (type == null || !type.startsWith("agent:")) {
            throw new IllegalArgumentException("AgentEvent.type must start with 'agent:' but got: " + type);
        }
        if (timestamp == null) timestamp = Instant.now();
        payload = (payload == null) ? Map.of() : Map.copyOf(payload);
    }

    public static AgentEvent of(String action, Long agentId, String traceId, int iteration,
                                Map<String, Object> payload) {
        return new AgentEvent("agent:" + action, Instant.now(), agentId, traceId, iteration, payload);
    }
}

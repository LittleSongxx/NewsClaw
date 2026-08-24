package vip.mate.hook.event;

import java.time.Instant;
import java.util.Map;

/**
 * 所有可订阅事件的密封根接口（RFC-017）。
 *
 * <p>sealed 锁定 7 个域事件，避免反射遍历；每个实现都是不可变 record 便于跨线程传递。
 * 派发器 {@code HookDispatcher} 监听此接口并按 {@link #type()} 做 O(1) 索引匹配。</p>
 *
 * <p><b>命名约定</b>：{@code type()} 形如 {@code <domain>:<action>}，例如
 * {@code agent:start}、{@code tool:after}、{@code wiki:processed}。</p>
 */
public sealed interface MateHookEvent
        permits AgentEvent, ToolEvent, SessionEvent, ChannelEvent,
                MemoryEvent, WikiEvent, CronEvent {

    /** 事件类型，形如 domain:action；订阅方据此匹配。 */
    String type();

    /** 事件发生时间（UTC）。 */
    Instant timestamp();

    /** 结构化载荷；订阅方可读，不应修改（record 已深拷贝保证）。 */
    Map<String, Object> payload();
}

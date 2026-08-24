package vip.mate.llm.cache;

/**
 * 缓存断点的语义类型。
 * <p>断点位置用于序列化器决定把 {@code cache_control} 放在请求体的哪个内容块上。
 * 顺序与 Anthropic Messages API 的天然布局对齐：system → tools → memory/wiki → messages tail。</p>
 */
public enum BreakpointKind {
    SYSTEM_TAIL,
    TOOLS_TAIL,
    MEMORY_BLOCK,
    MESSAGES_TAIL
}

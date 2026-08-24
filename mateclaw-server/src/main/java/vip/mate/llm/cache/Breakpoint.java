package vip.mate.llm.cache;

/**
 * 一个缓存断点：序列化器据此决定在哪条消息（或 system / tools 段）上挂 cache_control。
 *
 * @param messageIndex 在最终 messages 数组中的索引；对 SYSTEM_TAIL / TOOLS_TAIL 取 -1
 * @param kind         断点语义
 */
public record Breakpoint(int messageIndex, BreakpointKind kind) {
    public static Breakpoint systemTail() { return new Breakpoint(-1, BreakpointKind.SYSTEM_TAIL); }
    public static Breakpoint toolsTail()  { return new Breakpoint(-1, BreakpointKind.TOOLS_TAIL); }
    public static Breakpoint memoryBlock(int idx) { return new Breakpoint(idx, BreakpointKind.MEMORY_BLOCK); }
    public static Breakpoint messagesTail(int idx) { return new Breakpoint(idx, BreakpointKind.MESSAGES_TAIL); }
}

package vip.newsclaw.memory.governance;

/** Untrusted candidate memory write submitted by a tool or background extractor. */
public record MemoryWriteRequest(
        Long workspaceId,
        Long agentId,
        String ownerKey,
        String memoryType,
        String memoryKey,
        String content,
        String source,
        String sourceConversationId,
        String sourceRef
) {
}

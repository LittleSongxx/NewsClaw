package vip.mate.memory.governance;

/** Result of a memory-admission decision. Only a pending admission may be written. */
public record MemoryWriteDecision(
        boolean admitted,
        Long ledgerId,
        String status,
        String reason
) {
    public static MemoryWriteDecision allowed(Long ledgerId) {
        return new MemoryWriteDecision(true, ledgerId, MemoryWriteGovernanceService.STATUS_PENDING, null);
    }

    public static MemoryWriteDecision denied(Long ledgerId, String status, String reason) {
        return new MemoryWriteDecision(false, ledgerId, status, reason);
    }

    public static MemoryWriteDecision bypassed() {
        return new MemoryWriteDecision(true, null, "BYPASSED", null);
    }
}

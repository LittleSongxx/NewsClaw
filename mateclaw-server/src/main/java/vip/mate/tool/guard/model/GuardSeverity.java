package vip.mate.tool.guard.model;

/**
 * 安全风险等级
 */
public enum GuardSeverity {

    CRITICAL(5),
    HIGH(4),
    MEDIUM(3),
    LOW(2),
    INFO(1);

    private final int weight;

    GuardSeverity(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public boolean isAtLeast(GuardSeverity threshold) {
        return this.weight >= threshold.weight;
    }

    /**
     * 取两个等级中较高的一个
     */
    public GuardSeverity max(GuardSeverity other) {
        return this.weight >= other.weight ? this : other;
    }
}

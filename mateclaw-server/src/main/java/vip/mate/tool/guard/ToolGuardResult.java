package vip.mate.tool.guard;

/**
 * 工具安全检查结果
 */
public record ToolGuardResult(
        Action action,
        String reason,
        String matchedPattern
) {

    public enum Action {
        ALLOW,
        BLOCK,
        /** 需要用户审批后才能执行（执行前授权机制） */
        NEEDS_APPROVAL
    }

    public static ToolGuardResult allow() {
        return new ToolGuardResult(Action.ALLOW, null, null);
    }

    public static ToolGuardResult block(String reason, String matchedPattern) {
        return new ToolGuardResult(Action.BLOCK, reason, matchedPattern);
    }

    public static ToolGuardResult needsApproval(String reason, String matchedPattern) {
        return new ToolGuardResult(Action.NEEDS_APPROVAL, reason, matchedPattern);
    }

    public boolean isBlocked() {
        return action == Action.BLOCK;
    }

    public boolean needsApproval() {
        return action == Action.NEEDS_APPROVAL;
    }
}

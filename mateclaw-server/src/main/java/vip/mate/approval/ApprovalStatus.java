package vip.mate.approval;

/**
 * 审批状态枚举
 */
public enum ApprovalStatus {

    PENDING,
    APPROVED,
    DENIED,
    CONSUMED,
    TIMEOUT,
    SUPERSEDED;

    /**
     * 从字符串解析（兼容现有 PendingApproval 的 status 字段）
     */
    public static ApprovalStatus fromString(String status) {
        if (status == null) return PENDING;
        try {
            return valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}

package vip.mate.tool.guard.model;

/**
 * 安全裁决结果
 */
public enum GuardDecision {

    /** 允许执行 */
    ALLOW,

    /** 需要用户审批后才能执行 */
    NEEDS_APPROVAL,

    /** 直接阻断，不允许审批覆盖 */
    BLOCK
}

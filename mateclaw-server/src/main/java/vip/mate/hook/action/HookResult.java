package vip.mate.hook.action;

import java.time.Duration;

/**
 * hook 执行结果，记录审计用。
 *
 * @param status       执行状态
 * @param message      简短说明（成功的行号、失败原因等）
 * @param durationMs   执行耗时
 */
public record HookResult(Status status, String message, long durationMs) {

    public enum Status { SUCCESS, FAILED, TIMEOUT, RATE_LIMITED, BLOCKED }

    public static HookResult success(long durationMs) {
        return new HookResult(Status.SUCCESS, null, durationMs);
    }

    public static HookResult success(String message, long durationMs) {
        return new HookResult(Status.SUCCESS, message, durationMs);
    }

    public static HookResult failed(String message, long durationMs) {
        return new HookResult(Status.FAILED, message, durationMs);
    }

    public static HookResult timeout(long durationMs) {
        return new HookResult(Status.TIMEOUT, "deadline exceeded", durationMs);
    }

    public static HookResult rateLimited() {
        return new HookResult(Status.RATE_LIMITED, "rate limit exceeded", 0L);
    }

    public static HookResult blocked(String reason) {
        return new HookResult(Status.BLOCKED, reason, 0L);
    }

    public static HookResult fromDuration(Duration d) {
        return success(d.toMillis());
    }
}

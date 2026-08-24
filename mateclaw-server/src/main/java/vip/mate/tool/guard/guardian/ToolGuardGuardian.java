package vip.mate.tool.guard.guardian;

import vip.mate.tool.guard.model.GuardFinding;
import vip.mate.tool.guard.model.ToolInvocationContext;

import java.util.List;

/**
 * 工具安全守卫接口
 * <p>
 * 每个 Guardian 负责一类风险的检测。
 * Guardian 只产出 findings（事实），不做最终裁决。
 * 最终裁决由 ToolPolicyResolver 负责。
 */
public interface ToolGuardGuardian {

    /**
     * 是否适用于此次工具调用
     */
    boolean supports(ToolInvocationContext context);

    /**
     * 评估工具调用，返回发现列表
     *
     * @param context 工具调用上下文
     * @return 风险发现列表（空列表表示无风险）
     */
    List<GuardFinding> evaluate(ToolInvocationContext context);

    /**
     * 优先级（数值越大越先执行）
     */
    default int priority() {
        return 100;
    }

    /**
     * 是否始终运行（不受 guarded tools 范围限制）
     */
    default boolean alwaysRun() {
        return false;
    }

    /**
     * Guardian 名称
     */
    default String name() {
        return getClass().getSimpleName();
    }
}

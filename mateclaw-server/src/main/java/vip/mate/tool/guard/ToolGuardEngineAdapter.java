package vip.mate.tool.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.engine.ToolGuardEngine;
import vip.mate.tool.guard.model.GuardDecision;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.ToolInvocationContext;
import vip.mate.tool.guard.service.ToolGuardConfigService;

import java.util.Set;

/**
 * ToolGuard 引擎适配器
 * <p>
 * 使用 @Primary 接管现有 ToolGuard 接口。
 * 内部委托给新的 ToolGuardEngine 做评估，将 GuardEvaluation 映射回 ToolGuardResult。
 * <p>
 * 增加全局开关和 deniedTools 检查（来自 ToolGuardConfigService）。
 * <p>
 * 设计目的：零破坏性切换。移除 @Primary 即可回退到 DefaultToolGuard。
 */
@Slf4j
@Primary
@Component
public class ToolGuardEngineAdapter implements ToolGuard {

    private final ToolGuardEngine engine;
    private final ToolGuardConfigService configService;

    public ToolGuardEngineAdapter(ToolGuardEngine engine, ToolGuardConfigService configService) {
        this.engine = engine;
        this.configService = configService;
        log.info("[ToolGuardEngineAdapter] Active — new guardian engine is now handling all tool guard checks");
    }

    @Override
    public ToolGuardResult check(String toolName, String arguments) {
        // 全局开关：guard 禁用时直接放行
        if (!configService.isEnabled()) {
            return ToolGuardResult.allow();
        }

        // 黑名单工具：直接拦截
        Set<String> denied = configService.getDeniedTools();
        if (!denied.isEmpty() && denied.contains(toolName)) {
            return ToolGuardResult.block("工具 " + toolName + " 已被安全策略禁用", null);
        }

        ToolInvocationContext context = ToolInvocationContext.of(toolName, arguments, null, null);
        GuardEvaluation evaluation = engine.evaluate(context);
        return toResult(evaluation);
    }

    /**
     * 带完整上下文的检查方法
     */
    public GuardEvaluation evaluateFull(ToolInvocationContext context) {
        if (!configService.isEnabled()) {
            return GuardEvaluation.allow(context.toolName());
        }

        Set<String> denied = configService.getDeniedTools();
        if (!denied.isEmpty() && denied.contains(context.toolName())) {
            return new GuardEvaluation(
                    context.toolName(), java.util.List.of(),
                    null, GuardDecision.BLOCK,
                    "工具 " + context.toolName() + " 已被安全策略禁用");
        }

        return engine.evaluate(context);
    }

    private ToolGuardResult toResult(GuardEvaluation evaluation) {
        if (evaluation.decision() == GuardDecision.BLOCK) {
            String reason = evaluation.summary() != null ? evaluation.summary() : "安全策略阻断";
            String pattern = evaluation.hasFindings()
                    ? evaluation.findings().get(0).matchedPattern()
                    : null;
            return ToolGuardResult.block(reason, pattern);
        }

        if (evaluation.decision() == GuardDecision.NEEDS_APPROVAL) {
            String reason = evaluation.summary() != null ? evaluation.summary() : "需要用户审批";
            String pattern = evaluation.hasFindings()
                    ? evaluation.findings().get(0).matchedPattern()
                    : "default_approval";
            return ToolGuardResult.needsApproval(reason, pattern);
        }

        return ToolGuardResult.allow();
    }
}

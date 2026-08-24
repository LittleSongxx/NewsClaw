package vip.mate.tool.guard.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.tool.guard.guardian.ToolGuardGuardian;
import vip.mate.tool.guard.model.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工具安全守卫引擎
 * <p>
 * 编排所有 Guardian，聚合 findings，通过 PolicyResolver 产出最终裁决。
 * <ul>
 *   <li>Guardian 只负责产出 findings（事实）</li>
 *   <li>PolicyResolver 负责把 findings 映射为最终 action</li>
 *   <li>Engine 负责编排和聚合</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolGuardEngine {

    private final List<ToolGuardGuardian> guardians;
    private final ToolPolicyResolver policyResolver;

    public ToolGuardEngine(List<ToolGuardGuardian> guardians, ToolPolicyResolver policyResolver) {
        // 按 priority 降序排列
        this.guardians = guardians.stream()
                .sorted(Comparator.comparingInt(ToolGuardGuardian::priority).reversed())
                .toList();
        this.policyResolver = policyResolver;
        log.info("[ToolGuardEngine] Initialized with {} guardians: {}",
                this.guardians.size(),
                this.guardians.stream().map(ToolGuardGuardian::name).toList());
    }

    /**
     * 评估工具调用
     *
     * @param context 工具调用上下文
     * @return 聚合评估结果
     */
    public GuardEvaluation evaluate(ToolInvocationContext context) {
        if (context.toolName() == null || context.toolName().isEmpty()) {
            return GuardEvaluation.allow(context.toolName());
        }

        List<GuardFinding> allFindings = new ArrayList<>();

        for (ToolGuardGuardian guardian : guardians) {
            try {
                if (guardian.alwaysRun() || guardian.supports(context)) {
                    List<GuardFinding> findings = guardian.evaluate(context);
                    if (findings != null && !findings.isEmpty()) {
                        allFindings.addAll(findings);
                        log.debug("[ToolGuardEngine] {} produced {} findings for tool={}",
                                guardian.name(), findings.size(), context.toolName());
                    }
                }
            } catch (Exception e) {
                log.warn("[ToolGuardEngine] Guardian {} failed for tool={}: {}",
                        guardian.name(), context.toolName(), e.getMessage());
                // 单个 guardian 异常不中断其他
            }
        }

        // 通过 policy resolver 产出最终裁决
        GuardDecision decision = policyResolver.resolve(allFindings, context);
        GuardSeverity maxSeverity = computeMaxSeverity(allFindings);
        String summary = policyResolver.buildSummary(allFindings, decision);

        GuardEvaluation evaluation = new GuardEvaluation(
                context.toolName(), List.copyOf(allFindings), maxSeverity, decision, summary
        );

        if (decision != GuardDecision.ALLOW) {
            log.info("[ToolGuardEngine] tool={}, decision={}, maxSeverity={}, findings={}",
                    context.toolName(), decision, maxSeverity,
                    allFindings.size());
        }

        return evaluation;
    }

    private GuardSeverity computeMaxSeverity(List<GuardFinding> findings) {
        GuardSeverity max = null;
        for (GuardFinding f : findings) {
            if (f.severity() != null) {
                max = (max == null) ? f.severity() : max.max(f.severity());
            }
        }
        return max;
    }

    public List<ToolGuardGuardian> getGuardians() {
        return guardians;
    }
}

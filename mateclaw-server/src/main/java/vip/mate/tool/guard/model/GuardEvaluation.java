package vip.mate.tool.guard.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具调用安全评估结果
 * <p>
 * 由 ToolGuardEngine 聚合所有 Guardian 的 findings 后产出。
 * 包含最终裁决和完整的风险上下文。
 */
public record GuardEvaluation(
        String toolName,
        List<GuardFinding> findings,
        GuardSeverity maxSeverity,
        GuardDecision decision,
        String summary
) {

    /**
     * 快速创建一个 ALLOW 评估（无任何发现）
     */
    public static GuardEvaluation allow(String toolName) {
        return new GuardEvaluation(toolName, List.of(), null, GuardDecision.ALLOW, null);
    }

    public boolean shouldBlock() {
        return decision == GuardDecision.BLOCK;
    }

    public boolean shouldRequireApproval() {
        return decision == GuardDecision.NEEDS_APPROVAL;
    }

    public boolean isAllowed() {
        return decision == GuardDecision.ALLOW;
    }

    public boolean hasFindings() {
        return findings != null && !findings.isEmpty();
    }

    /**
     * 转为可序列化的 findings 列表（用于 SSE 事件）
     */
    public List<Map<String, Object>> findingsToMapList() {
        if (findings == null) return List.of();
        return findings.stream()
                .map(GuardFinding::toMap)
                .collect(Collectors.toList());
    }
}

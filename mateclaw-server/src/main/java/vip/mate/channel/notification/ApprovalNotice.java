package vip.mate.channel.notification;

import java.util.List;
import java.util.Map;

/**
 * 审批通知数据载体
 * <p>
 * 统一渠道通知模型，从 PendingApproval 元数据构建。
 */
public record ApprovalNotice(
        String pendingId,
        String toolName,
        String summary,
        String argumentsPreview,
        String maxSeverity,
        List<Map<String, Object>> findings,
        String approveCommand,
        String denyCommand
) {
}

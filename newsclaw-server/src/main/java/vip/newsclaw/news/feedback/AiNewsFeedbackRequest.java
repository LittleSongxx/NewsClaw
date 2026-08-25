package vip.newsclaw.news.feedback;

/**
 * Untrusted feedback payload. Skill fields are optional: a reviewer can first
 * record a badcase and later attribute it to a Skill through a proposal.
 */
public record AiNewsFeedbackRequest(
        Long workspaceId,
        Long eventId,
        Long teamRunId,
        Long taskId,
        String feedbackType,
        String note,
        String evidenceJson,
        String skillName,
        String proposalAction,
        String content,
        String oldText,
        String newText
) {
}

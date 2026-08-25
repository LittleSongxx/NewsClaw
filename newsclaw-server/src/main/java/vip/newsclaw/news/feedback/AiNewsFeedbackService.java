package vip.newsclaw.news.feedback;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.repository.AiNewsFeedbackMapper;
import vip.newsclaw.skill.proposal.SkillChangeProposalEntity;
import vip.newsclaw.skill.proposal.SkillProposalDraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Connects NewsClaw badcases to the existing proposal-first Skill control
 * plane. Recording feedback is always allowed; applying a Skill change still
 * requires the normal security scan, human approval, snapshot and rollback
 * path in {@link SkillChangeProposalService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNewsFeedbackService {

    private static final long DEFAULT_WORKSPACE = 1L;
    private final AiNewsFeedbackMapper feedbackMapper;
    private final AiNewsEventService eventService;
    private final AiNewsFeedbackProposalBridge proposalBridge;
    private final ObjectMapper objectMapper;

    @Transactional
    public FeedbackResult submit(Long workspaceId, AiNewsFeedbackRequest request) {
        if (request == null) throw new NewsClawException(400, "AI news feedback is required");
        long ws = workspace(workspaceId == null ? request.workspaceId() : workspaceId);
        if (workspaceId != null && request.workspaceId() != null
                && request.workspaceId() > 0 && !workspaceId.equals(request.workspaceId())) {
            throw new NewsClawException(400, "request workspaceId must match X-Workspace-Id");
        }
        if (request.eventId() != null) {
            // This is both an existence check and the tenant boundary. A
            // foreign event is surfaced as the same not-found error used by
            // the normal event API.
            eventService.findEvent(ws, request.eventId());
        }
        String type = normalize(request.feedbackType(), "BADCASE", 64);
        String note = requiredTrim(request.note(), "feedback note", 12_000);
        boolean hasSkillTarget = request.skillName() != null && !request.skillName().isBlank();
        boolean hasProposalAction = request.proposalAction() != null && !request.proposalAction().isBlank();
        if (hasSkillTarget != hasProposalAction) {
            throw new NewsClawException(400,
                    "skillName and proposalAction must be supplied together for a Skill proposal");
        }
        if (hasSkillTarget && hasProposalAction
                && !ALLOWED_ACTIONS.contains(request.proposalAction().trim().toLowerCase())) {
            throw new NewsClawException(400, "proposalAction must be create, edit or patch");
        }
        String hash = sha256(ws + "\n" + nullable(request.eventId()) + "\n"
                + nullable(request.teamRunId()) + "\n" + nullable(request.taskId()) + "\n"
                + type + "\n" + note + "\n" + nullable(request.skillName()) + "\n"
                + nullable(request.proposalAction()) + "\n" + nullable(request.content()) + "\n"
                + nullable(request.oldText()) + "\n" + nullable(request.newText()));
        AiNewsFeedbackEntity existing = feedbackMapper.selectOne(new LambdaQueryWrapper<AiNewsFeedbackEntity>()
                .eq(AiNewsFeedbackEntity::getWorkspaceId, ws)
                .eq(AiNewsFeedbackEntity::getFeedbackHash, hash)
                .eq(AiNewsFeedbackEntity::getDeleted, 0));
        if (existing != null) {
            return new FeedbackResult(existing, existing.getProposalId() == null
                    ? null : safeProposal(ws, existing.getProposalId()));
        }

        AiNewsFeedbackEntity feedback = new AiNewsFeedbackEntity();
        feedback.setWorkspaceId(ws);
        feedback.setFeedbackHash(hash);
        feedback.setEventId(request.eventId());
        feedback.setTeamRunId(request.teamRunId());
        feedback.setTaskId(request.taskId());
        feedback.setFeedbackType(type);
        feedback.setNote(note);
        feedback.setEvidenceJson(normalizeEvidence(request));
        feedback.setSkillName(trim(request.skillName(), 128));
        feedback.setProposalAction(trim(request.proposalAction(), 16));
        feedback.setStatus("RECORDED");
        feedback.setDeleted(0);
        feedback.setCreateTime(LocalDateTime.now());
        feedback.setUpdateTime(LocalDateTime.now());
        feedbackMapper.insert(feedback);

        SkillChangeProposalEntity proposal = maybePropose(ws, feedback, request);
        if (proposal != null) {
            feedback.setProposalId(proposal.getId());
            feedback.setStatus("PROPOSAL_CREATED");
            feedback.setUpdateTime(LocalDateTime.now());
            feedbackMapper.updateById(feedback);
        } else if (!"RECORDED".equals(feedback.getStatus())) {
            feedback.setUpdateTime(LocalDateTime.now());
            feedbackMapper.updateById(feedback);
        }
        return new FeedbackResult(feedback, proposal);
    }

    private SkillChangeProposalEntity maybePropose(long workspaceId, AiNewsFeedbackEntity feedback,
                                                    AiNewsFeedbackRequest request) {
        if (request.skillName() == null || request.skillName().isBlank()
                || request.proposalAction() == null || request.proposalAction().isBlank()) {
            return null;
        }
        String action = request.proposalAction().trim().toLowerCase();
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new NewsClawException(400, "proposalAction must be create, edit or patch");
        }
        String sourceConversation = request.eventId() == null
                ? null : "ai-news-event-" + request.eventId();
        try {
            return proposalBridge.propose(new SkillProposalDraft(
                    workspaceId, null, "AI_NEWS_FEEDBACK", sourceConversation,
                    request.taskId() == null ? request.teamRunId() : request.taskId(),
                    action, request.skillName(), request.content(), request.oldText(), request.newText(),
                    feedback.getEvidenceJson()));
        } catch (RuntimeException e) {
            // Keep the feedback row even when a proposed diff is invalid; the
            // reviewer can correct attribution/content and resubmit later.
            log.info("AI-news feedback {} recorded without proposal: {}",
                    feedback.getId(), e.getMessage());
            feedback.setStatus("PROPOSAL_REJECTED_INPUT");
            return null;
        }
    }

    private String normalizeEvidence(AiNewsFeedbackRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submittedEvidence", request.evidenceJson() == null ? "" : request.evidenceJson());
        payload.put("eventId", request.eventId());
        payload.put("teamRunId", request.teamRunId());
        payload.put("taskId", request.taskId());
        payload.put("feedbackType", request.feedbackType());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"submittedEvidence\":\"unserializable\"}";
        }
    }

    private SkillChangeProposalEntity safeProposal(long workspaceId, Long id) {
        try {
            return proposalBridge.get(workspaceId, id);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final java.util.Set<String> ALLOWED_ACTIONS = java.util.Set.of("create", "edit", "patch");

    private static long workspace(Long value) {
        return value == null || value <= 0 ? DEFAULT_WORKSPACE : value;
    }

    private static String requiredTrim(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new NewsClawException(400, field + " is required");
        return trim(value, max);
    }

    private static String normalize(String value, String fallback, int max) {
        return trim(value == null || value.isBlank() ? fallback : value.trim().toUpperCase(), max);
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static String nullable(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record FeedbackResult(AiNewsFeedbackEntity feedback,
                                 SkillChangeProposalEntity proposal) {
    }
}

package vip.mate.skill.proposal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;
import vip.mate.skill.lifecycle.SkillSnapshotService;
import vip.mate.skill.lifecycle.model.SkillSnapshotEntity;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillOrigin;
import vip.mate.skill.runtime.SkillSecurityService;
import vip.mate.skill.runtime.SkillValidationResult;
import vip.mate.skill.routine.model.SkillRoutineCandidateEntity;
import vip.mate.skill.routine.repository.SkillRoutineCandidateMapper;
import vip.mate.skill.service.SkillService;
import vip.mate.tool.builtin.SkillManageTool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proposal-first control plane for autonomous Skill evolution.
 *
 * <p>Reflection and routine mining may only persist a candidate here. A
 * workspace administrator explicitly approves the exact diff; application
 * takes a snapshot first and re-enters {@link SkillManageTool}'s normal
 * security/export/runtime-refresh pipeline. The service deliberately does not
 * call an LLM, so review decisions remain deterministic and auditable.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillChangeProposalService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_APPLYING = "APPLYING";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_APPLIED = "APPLIED";

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    /** Must stay aligned with SkillManageTool's live-write limit. */
    private static final int MAX_CONTENT_CHARS = 100_000;

    private final SkillChangeProposalMapper proposalMapper;
    private final SkillService skillService;
    private final SkillSecurityService securityService;
    private final SkillSnapshotService snapshotService;
    private final SkillManageTool skillManageTool;
    private final ObjectMapper objectMapper;
    private final AuditEventService auditEventService;
    private final SkillRoutineCandidateMapper routineCandidateMapper;

    @Transactional
    public SkillChangeProposalEntity propose(SkillProposalDraft draft) {
        if (draft == null) {
            throw new MateClawException(400, "Skill proposal is required");
        }
        long workspaceId = workspace(draft.workspaceId());
        String action = normalizeAction(draft.action());
        String name = normalizeName(draft.skillName());
        SkillEntity existing = skillService.findByName(name, workspaceId);
        validateActionTarget(action, name, existing);
        String before = existing == null ? null : existing.getSkillContent();
        String after = materializeAfter(action, draft, before);
        if (after.length() > MAX_CONTENT_CHARS) {
            throw new MateClawException(400, "Skill proposal content exceeds " + MAX_CONTENT_CHARS + " characters");
        }
        if (nullToEmpty(before).equals(after)) {
            throw new MateClawException(409, "Skill proposal does not change the current content");
        }
        String proposalHash = sha256(workspaceId + "\n" + action + "\n" + name + "\n"
                + nullToEmpty(before) + "\n" + nullToEmpty(after));
        SkillChangeProposalEntity duplicate = proposalMapper.selectOne(new LambdaQueryWrapper<SkillChangeProposalEntity>()
                .eq(SkillChangeProposalEntity::getWorkspaceId, workspaceId)
                .eq(SkillChangeProposalEntity::getProposalHash, proposalHash)
                .eq(SkillChangeProposalEntity::getDeleted, 0));
        if (duplicate != null) {
            return duplicate;
        }

        SkillValidationResult validation = securityService.scanContent(after, name);
        String status = validation.isBlocked() ? STATUS_BLOCKED : STATUS_PENDING;
        SkillChangeProposalEntity proposal = new SkillChangeProposalEntity();
        proposal.setWorkspaceId(workspaceId);
        proposal.setProposalHash(proposalHash);
        proposal.setAgentId(draft.agentId());
        proposal.setSourceType(trim(defaultValue(draft.sourceType(), "MANUAL"), 32));
        proposal.setSourceConversationId(trim(draft.sourceConversationId(), 256));
        proposal.setSourceRunId(draft.sourceRunId());
        proposal.setAction(action);
        proposal.setSkillName(name);
        proposal.setBeforeContent(before);
        proposal.setAfterContent(after);
        proposal.setDiffText(renderDiff(before, after));
        proposal.setEvidenceJson(mergeEvidence(draft.evidenceJson(), validation));
        proposal.setRiskLevel(validation.getMaxSeverity() == null
                ? "LOW" : validation.getMaxSeverity().name());
        proposal.setStatus(status);
        proposal.setDeleted(0);
        proposalMapper.insert(proposal);
        audit(status.equals(STATUS_BLOCKED) ? "SKILL_PROPOSAL_BLOCKED" : "SKILL_PROPOSAL_CREATED",
                proposal, Map.of("source", proposal.getSourceType(), "risk", proposal.getRiskLevel()));
        return proposal;
    }

    public IPage<SkillChangeProposalEntity> page(Long workspaceId, int page, int size, String status) {
        LambdaQueryWrapper<SkillChangeProposalEntity> query = new LambdaQueryWrapper<SkillChangeProposalEntity>()
                .eq(SkillChangeProposalEntity::getWorkspaceId, workspace(workspaceId))
                .eq(SkillChangeProposalEntity::getDeleted, 0);
        if (status != null && !status.isBlank()) {
            query.eq(SkillChangeProposalEntity::getStatus, status.trim().toUpperCase());
        }
        query.orderByDesc(SkillChangeProposalEntity::getCreateTime);
        return proposalMapper.selectPage(new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 100)), query);
    }

    public SkillChangeProposalEntity get(Long workspaceId, Long id) {
        SkillChangeProposalEntity proposal = proposalMapper.selectOne(new LambdaQueryWrapper<SkillChangeProposalEntity>()
                .eq(SkillChangeProposalEntity::getId, id)
                .eq(SkillChangeProposalEntity::getWorkspaceId, workspace(workspaceId))
                .eq(SkillChangeProposalEntity::getDeleted, 0));
        if (proposal == null) {
            throw new MateClawException(404, "Skill proposal not found");
        }
        return proposal;
    }

    @Transactional
    public SkillChangeProposalEntity approve(Long workspaceId, Long id, SkillProposalReviewRequest review) {
        SkillChangeProposalEntity proposal = get(workspaceId, id);
        if (STATUS_BLOCKED.equals(proposal.getStatus())) {
            throw new MateClawException(409, "Security-blocked proposal cannot be approved");
        }
        if (STATUS_REJECTED.equals(proposal.getStatus())) {
            throw new MateClawException(409, "Rejected proposal cannot be approved");
        }
        if (!STATUS_APPLIED.equals(proposal.getStatus())) {
            proposal.setStatus(STATUS_APPROVED);
            proposal.setReviewer(trim(review == null ? null : review.reviewer(), 128));
            proposal.setReviewNote(trim(review == null ? null : review.note(), 1000));
            proposal.setReviewedAt(LocalDateTime.now());
            proposalMapper.updateById(proposal);
            audit("SKILL_PROPOSAL_APPROVED", proposal, Map.of("applyNow",
                    review != null && Boolean.TRUE.equals(review.applyNow())));
        }
        if (review != null && Boolean.TRUE.equals(review.applyNow()) && !STATUS_APPLIED.equals(proposal.getStatus())) {
            return apply(workspaceId, id);
        }
        return proposal;
    }

    @Transactional
    public SkillChangeProposalEntity reject(Long workspaceId, Long id, SkillProposalReviewRequest review) {
        SkillChangeProposalEntity proposal = get(workspaceId, id);
        if (STATUS_APPLIED.equals(proposal.getStatus())) {
            throw new MateClawException(409, "Applied proposal must be rolled back, not rejected");
        }
        proposal.setStatus(STATUS_REJECTED);
        proposal.setReviewer(trim(review == null ? null : review.reviewer(), 128));
        proposal.setReviewNote(trim(review == null ? null : review.note(), 1000));
        proposal.setReviewedAt(LocalDateTime.now());
        proposalMapper.updateById(proposal);
        audit("SKILL_PROPOSAL_REJECTED", proposal, Map.of());
        return proposal;
    }

    /** Apply an approved proposal through the normal skill_manage write path. */
    @Transactional
    public SkillChangeProposalEntity apply(Long workspaceId, Long id) {
        SkillChangeProposalEntity proposal = get(workspaceId, id);
        if (STATUS_APPLIED.equals(proposal.getStatus())) {
            return proposal;
        }
        if (!STATUS_APPROVED.equals(proposal.getStatus())) {
            throw new MateClawException(409, "Only an approved proposal can be applied");
        }
        int claimed = proposalMapper.update(null, new LambdaUpdateWrapper<SkillChangeProposalEntity>()
                .eq(SkillChangeProposalEntity::getId, proposal.getId())
                .eq(SkillChangeProposalEntity::getWorkspaceId, proposal.getWorkspaceId())
                .eq(SkillChangeProposalEntity::getStatus, STATUS_APPROVED)
                .eq(SkillChangeProposalEntity::getDeleted, 0)
                .set(SkillChangeProposalEntity::getStatus, STATUS_APPLYING));
        if (claimed != 1) {
            SkillChangeProposalEntity current = get(workspaceId, id);
            if (STATUS_APPLIED.equals(current.getStatus())) {
                return current;
            }
            throw new MateClawException(409, "Skill proposal is being applied by another request");
        }
        proposal.setStatus(STATUS_APPLYING);
        SkillSnapshotEntity snapshot = snapshotService.captureRequired(
                "pre-skill-proposal " + proposal.getId(), proposal.getWorkspaceId());
        ToolContext context = toolContext(proposal);
        String toolAction = "create".equals(proposal.getAction()) ? "create" : "edit";
        String result = skillManageTool.skillManageAs(SkillOrigin.AGENT, toolAction, proposal.getSkillName(),
                proposal.getAfterContent(), null, null, null, context);
        if (!isSuccess(result)) {
            throw new MateClawException(409, "Skill proposal could not be applied: " + trim(result, 800));
        }
        SkillEntity applied = skillService.findByName(proposal.getSkillName(), proposal.getWorkspaceId());
        proposal.setStatus(STATUS_APPLIED);
        proposal.setSnapshotId(snapshot == null ? null : snapshot.getId());
        proposal.setAppliedSkillId(applied == null ? null : applied.getId());
        proposal.setAppliedVersion(applied == null ? null : applied.getVersion());
        proposal.setAppliedAt(LocalDateTime.now());
        proposal.setRollbackStatus(null);
        proposalMapper.updateById(proposal);
        syncRoutineCandidate(proposal, SkillRoutineCandidateEntity.STATUS_PROPOSED,
                SkillRoutineCandidateEntity.STATUS_PROMOTED);
        audit("SKILL_PROPOSAL_APPLIED", proposal, Map.of("snapshotId",
                proposal.getSnapshotId() == null ? "" : String.valueOf(proposal.getSnapshotId())));
        return proposal;
    }

    @Transactional
    public SkillChangeProposalEntity rollback(Long workspaceId, Long id, String reviewer, String note) {
        SkillChangeProposalEntity proposal = get(workspaceId, id);
        if (!STATUS_APPLIED.equals(proposal.getStatus())) {
            throw new MateClawException(409, "Only an applied proposal can be rolled back");
        }
        if ("ROLLED_BACK".equals(proposal.getRollbackStatus())) {
            return proposal;
        }
        if (proposal.getSnapshotId() != null) {
            snapshotService.restore(proposal.getSnapshotId(), proposal.getWorkspaceId());
        } else if ("create".equals(proposal.getAction())) {
            String result = skillManageTool.skillManageAs(SkillOrigin.AGENT, "delete", proposal.getSkillName(),
                    null, null, null, null, toolContext(proposal));
            if (!isSuccess(result)) {
                throw new MateClawException(409, "Created skill could not be archived during rollback: " + trim(result, 800));
            }
        } else {
            throw new MateClawException(409, "Proposal has no usable restore snapshot");
        }
        proposal.setRollbackStatus("ROLLED_BACK");
        proposal.setReviewer(trim(reviewer, 128));
        proposal.setReviewNote(trim(note, 1000));
        proposal.setRolledBackAt(LocalDateTime.now());
        proposalMapper.updateById(proposal);
        syncRoutineCandidate(proposal, SkillRoutineCandidateEntity.STATUS_PROMOTED,
                SkillRoutineCandidateEntity.STATUS_PROPOSED);
        audit("SKILL_PROPOSAL_ROLLED_BACK", proposal, Map.of());
        return proposal;
    }

    /** Keep the mined routine projection aligned with its reviewable proposal. */
    private void syncRoutineCandidate(SkillChangeProposalEntity proposal, String expectedStatus, String targetStatus) {
        if (proposal.getSourceRunId() == null || proposal.getId() == null
                || !"ROUTINE".equalsIgnoreCase(proposal.getSourceType())) {
            return;
        }
        int updated = routineCandidateMapper.update(null,
                new LambdaUpdateWrapper<SkillRoutineCandidateEntity>()
                        .eq(SkillRoutineCandidateEntity::getId, proposal.getSourceRunId())
                        .eq(SkillRoutineCandidateEntity::getWorkspaceId, proposal.getWorkspaceId())
                        .eq(SkillRoutineCandidateEntity::getProposalId, proposal.getId())
                        .eq(SkillRoutineCandidateEntity::getStatus, expectedStatus)
                        .set(SkillRoutineCandidateEntity::getStatus, targetStatus)
                        .set(SkillRoutineCandidateEntity::getPromotedSkillName, proposal.getSkillName()));
        if (updated == 0) {
            log.debug("[SkillProposal] Routine candidate projection unchanged for proposal {} ({} -> {})",
                    proposal.getId(), expectedStatus, targetStatus);
        }
    }

    private ToolContext toolContext(SkillChangeProposalEntity proposal) {
        ChatOrigin origin = new ChatOrigin(proposal.getAgentId(), proposal.getSourceConversationId(), "",
                proposal.getWorkspaceId(), null, null, null, false,
                null, null, null, null, null);
        return new ToolContext(Map.of(ChatOrigin.CTX_KEY, origin));
    }

    private static boolean isSuccess(String result) {
        return result != null && !result.startsWith("Error") && !result.startsWith("Security scan BLOCKED");
    }

    private String mergeEvidence(String evidence, SkillValidationResult validation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceEvidence", evidence == null ? "" : evidence);
        payload.put("securityPassed", validation.isPassed());
        payload.put("securityBlocked", validation.isBlocked());
        payload.put("securitySummary", validation.getSummary());
        payload.put("securityFindings", validation.getFindings());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"securityBlocked\":" + validation.isBlocked() + "}";
        }
    }

    private static String materializeAfter(String action, SkillProposalDraft draft, String before) {
        return switch (action) {
            case "create", "edit" -> requireContent(draft.content());
            case "patch" -> patchOnce(before, draft.oldText(), draft.newText());
            default -> throw new MateClawException(400, "Unsupported Skill proposal action: " + action);
        };
    }

    private static void validateActionTarget(String action, String name, SkillEntity existing) {
        if ("create".equals(action) && existing != null) {
            throw new MateClawException(409, "Skill '" + name + "' already exists; use edit or patch");
        }
        if (!"create".equals(action) && existing == null) {
            throw new MateClawException(404, "Skill '" + name + "' does not exist; use create");
        }
        if (existing != null && Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new MateClawException(409, "Builtin Skill '" + name + "' cannot be changed by a proposal");
        }
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new MateClawException(400, "Proposal content is required");
        }
        return content.trim();
    }

    private static String patchOnce(String before, String oldText, String newText) {
        if (before == null || before.isBlank() || oldText == null || oldText.isBlank() || newText == null) {
            throw new MateClawException(400, "Patch proposal needs an existing Skill, oldText and newText");
        }
        int first = before.indexOf(oldText);
        if (first < 0 || before.indexOf(oldText, first + oldText.length()) >= 0) {
            throw new MateClawException(409, "Patch proposal target must exist exactly once");
        }
        return before.substring(0, first) + newText + before.substring(first + oldText.length());
    }

    private static String normalizeAction(String value) {
        String action = value == null ? "" : value.trim().toLowerCase();
        if (!"create".equals(action) && !"edit".equals(action) && !"patch".equals(action)) {
            throw new MateClawException(400, "Only create, edit and patch Skill proposals are supported");
        }
        return action;
    }

    private static String normalizeName(String value) {
        String name = value == null ? "" : value.trim().toLowerCase();
        if (!name.matches("^[a-z0-9][a-z0-9._-]{0,63}$")) {
            throw new MateClawException(400, "Invalid Skill proposal name");
        }
        return name;
    }

    private static String renderDiff(String before, String after) {
        if (nullToEmpty(before).equals(nullToEmpty(after))) {
            return "(no content change)";
        }
        return "--- current\n+++ proposed\n@@\n- " + abbreviate(before) + "\n+ " + abbreviate(after);
    }

    private static String abbreviate(String value) {
        String normalized = nullToEmpty(value).replace("\n", "\\n");
        return normalized.length() <= 16_000 ? normalized : normalized.substring(0, 16_000) + "…";
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void audit(String action, SkillChangeProposalEntity proposal, Map<String, Object> detail) {
        try {
            auditEventService.record(action, "SKILL_PROPOSAL", String.valueOf(proposal.getId()),
                    proposal.getSkillName(), objectMapper.writeValueAsString(detail), proposal.getWorkspaceId());
        } catch (Exception e) {
            log.debug("[SkillProposal] audit failed: {}", e.getMessage());
        }
    }

    private static long workspace(Long value) {
        return value == null || value <= 0 ? DEFAULT_WORKSPACE_ID : value;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trim(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

package vip.newsclaw.news.feedback;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.skill.proposal.SkillChangeProposalEntity;
import vip.newsclaw.skill.proposal.SkillChangeProposalService;
import vip.newsclaw.skill.proposal.SkillProposalDraft;

/**
 * Runs a feedback-originated Skill proposal in its own transaction.
 *
 * <p>The feedback row is deliberately durable even when a proposed diff is
 * rejected by validation or the security scanner. Calling the existing
 * proposal service directly from the feedback transaction would let a
 * runtime exception mark the outer transaction rollback-only, losing the
 * badcase that explains what should be fixed.</p>
 */
@Service
@RequiredArgsConstructor
public class AiNewsFeedbackProposalBridge {

    private final SkillChangeProposalService proposalService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SkillChangeProposalEntity propose(SkillProposalDraft draft) {
        return proposalService.propose(draft);
    }

    public SkillChangeProposalEntity get(long workspaceId, long proposalId) {
        return proposalService.get(workspaceId, proposalId);
    }
}

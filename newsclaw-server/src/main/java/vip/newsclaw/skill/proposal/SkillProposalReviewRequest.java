package vip.newsclaw.skill.proposal;

/** Operator decision for a candidate Skill mutation. */
public record SkillProposalReviewRequest(String reviewer, String note, Boolean applyNow) {
}

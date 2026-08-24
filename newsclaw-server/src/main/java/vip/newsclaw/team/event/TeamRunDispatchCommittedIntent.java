package vip.newsclaw.team.event;

/** Requests a team dispatch after the run transaction commits. */
public record TeamRunDispatchCommittedIntent(Long teamId) {
}

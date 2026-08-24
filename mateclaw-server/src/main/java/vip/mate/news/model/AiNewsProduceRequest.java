package vip.mate.news.model;

/**
 * Explicit confirmation payload for moving an event into editorial work.
 *
 * <p>The empty-body form remains a state-only transition for API clients that
 * want to stage work.  The workbench sends {@code startTeamRun=true} after the
 * operator confirms the topic.</p>
 */
public record AiNewsProduceRequest(Boolean startTeamRun) {
}

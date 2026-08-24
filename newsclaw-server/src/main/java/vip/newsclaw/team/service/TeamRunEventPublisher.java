package vip.newsclaw.team.service;

import vip.newsclaw.team.model.TeamRunView;

/** Stable application boundary for team run lifecycle events. */
public interface TeamRunEventPublisher {

    void publishCancelled(TeamRunView run);
}

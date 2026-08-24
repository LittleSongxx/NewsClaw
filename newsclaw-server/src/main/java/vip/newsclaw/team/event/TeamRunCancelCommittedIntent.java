package vip.newsclaw.team.event;

import vip.newsclaw.team.model.TeamRunView;

import java.util.List;

/** Carries detached cancellation side effects across the transaction boundary. */
public record TeamRunCancelCommittedIntent(
        TeamRunView run,
        List<WorkerTask> workers
) {

    public TeamRunCancelCommittedIntent {
        workers = List.copyOf(workers);
    }

    public record WorkerTask(Long taskId, Integer taskNumber, String conversationId) {
    }
}

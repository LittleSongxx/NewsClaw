package vip.newsclaw.agent.runtime;

import reactor.core.publisher.Flux;
import vip.newsclaw.agent.AgentService;
import vip.newsclaw.agent.runtime.contract.RuntimeEvent;

/** Adapts provider event streams to the native chat stream contract. */
public final class RuntimeEventStreamAdapter {
    private RuntimeEventStreamAdapter() {}

    public static Flux<AgentService.StreamDelta> adapt(Flux<RuntimeEvent> events) {
        if (events == null) return Flux.empty();
        return events.map(RuntimeEventProjector::project);
    }
}

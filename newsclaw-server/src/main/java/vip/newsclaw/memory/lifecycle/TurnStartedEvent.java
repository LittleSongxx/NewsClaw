package vip.newsclaw.memory.lifecycle;

/**
 * Published after prefetchAll completes, before the LLM call.
 *
 * @param context the turn context
 * @author NewsClaw Team
 */
public record TurnStartedEvent(TurnContext context) {}

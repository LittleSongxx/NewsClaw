package vip.newsclaw.memory.lifecycle;

/**
 * Published after syncAll completes for a turn.
 *
 * @param context        the turn context
 * @param assistantReply the LLM response text
 * @author NewsClaw Team
 */
public record TurnCompletedEvent(TurnContext context, String assistantReply) {}

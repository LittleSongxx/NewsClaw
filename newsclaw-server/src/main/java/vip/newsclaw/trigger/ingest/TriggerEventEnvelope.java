package vip.newsclaw.trigger.ingest;

import java.util.Map;
import java.util.List;

/**
 * Generic event envelope used by upstream sources (channel webhooks,
 * agent-lifecycle hooks, workflow-completion hooks, ad-hoc REST callers)
 * to feed the trigger pipeline. The pipeline owns dedup / rate-limit /
 * bot-self filtering; sources only need to fill this record:
 *
 * <ul>
 *   <li>{@code workspaceId} — scopes which triggers can fire on this event.</li>
 *   <li>{@code patternType} — matched against {@code mate_trigger.pattern_type};
 *       the ingest looks up only triggers whose pattern type equals this.</li>
 *   <li>{@code eventId} — stable upstream identifier used as the dedup key
 *       when present; the ingest falls back to a content hash when blank.</li>
 *   <li>{@code senderId} — the upstream actor; used by the bot-self filter
 *       to drop events that originate from NewsClaw's own outbound traffic.</li>
 *   <li>{@code data} — free-form payload exposed to the trigger's payload
 *       template under {@code event.*}.</li>
 *   <li>Optional conversation/channel/chat fields preserve source provenance
 *       when the event starts a durable workflow run.</li>
 * </ul>
 */
public record TriggerEventEnvelope(
        long workspaceId,
        String patternType,
        String eventId,
        String senderId,
        Map<String, Object> data,
        /** Conversation that caused the event, when the source has one. */
        String conversationId,
        /** Internal channel row id, when the source is an inbound channel. */
        Long channelId,
        /** External channel type (feishu / wecom / webhook, ...). */
        String channelType,
        /** External chat / room id used as the delivery target. */
        String chatId,
        /** Human-readable sender name, when supplied by the source. */
        String senderName,
        /**
         * True only for an event emitted by a channel adapter after that
         * adapter authenticated and mapped the channel to a workspace.  REST
         * envelopes and generic webhooks are untrusted input; they must not
         * be able to impersonate an editorial human merely by choosing a
         * senderId.
         */
        boolean trustedSource,
        /** Trigger ids already traversed by this event chain. */
        List<Long> triggerAncestry,
        /** Number of trigger-to-workflow hops already traversed. */
        int triggerDepth
) {
    /**
     * Backward-compatible constructor for generic callers that only have the
     * original five envelope fields.
     */
    public TriggerEventEnvelope(long workspaceId, String patternType,
                                String eventId, String senderId,
                                Map<String, Object> data) {
        this(workspaceId, patternType, eventId, senderId, data,
                null, null, null, null, null, false, List.of(), 0);
    }

    /** Convenience constructor for sources that only know the conversation. */
    public TriggerEventEnvelope(long workspaceId, String patternType,
                                String eventId, String senderId,
                                Map<String, Object> data,
                                String conversationId) {
        this(workspaceId, patternType, eventId, senderId, data,
                conversationId, null, null, null, null, false, List.of(), 0);
    }

    /** Convenience constructor for the common channel metadata shape. */
    public TriggerEventEnvelope(long workspaceId, String patternType,
                                String eventId, String senderId,
                                Map<String, Object> data,
                                String conversationId, String channelType,
                                String chatId) {
        this(workspaceId, patternType, eventId, senderId, data,
                conversationId, null, channelType, chatId, null, false, List.of(), 0);
    }

    /** Convenience constructor for channel sources without an internal id. */
    public TriggerEventEnvelope(long workspaceId, String patternType,
                                String eventId, String senderId,
                                Map<String, Object> data,
                                String conversationId, String channelType,
                                String chatId, String senderName) {
        this(workspaceId, patternType, eventId, senderId, data,
                conversationId, null, channelType, chatId, senderName, false, List.of(), 0);
    }

    /** Backward-compatible constructor for the previous ten-field shape. */
    public TriggerEventEnvelope(long workspaceId, String patternType,
                                String eventId, String senderId,
                                Map<String, Object> data,
                                String conversationId, Long channelId,
                                String channelType, String chatId,
                                String senderName) {
        this(workspaceId, patternType, eventId, senderId, data,
                conversationId, channelId, channelType, chatId, senderName,
                false, List.of(), 0);
    }

    /** Backward-compatible constructor for trusted channel bridges. */
    public TriggerEventEnvelope(long workspaceId, String patternType,
                                String eventId, String senderId,
                                Map<String, Object> data,
                                String conversationId, Long channelId,
                                String channelType, String chatId,
                                String senderName, boolean trustedSource) {
        this(workspaceId, patternType, eventId, senderId, data,
                conversationId, channelId, channelType, chatId, senderName,
                trustedSource, List.of(), 0);
    }

    public TriggerEventEnvelope {
        data = data == null ? Map.of() : Map.copyOf(data);
        triggerAncestry = triggerAncestry == null ? List.of() : List.copyOf(triggerAncestry);
        triggerDepth = Math.max(0, triggerDepth);
    }
}

package vip.mate.channel.feishu.cards;

import vip.mate.channel.cards.CardOversizedException;

/**
 * Description of one kind of interactive Feishu card the dispatcher
 * knows how to route — its outbound render path plus its inbound click
 * handler.
 *
 * <p>Disjoint-prefix invariant on {@link #actionPrefix} — the inbound
 * dispatcher picks a handler by matching the prefix of the button
 * {@code value.action} string, so two card kinds MUST NOT share a
 * prefix (the dispatcher rejects collisions at registration time).
 * Kept as a simple record so adding a new kind is just: implement
 * renderer/handler, register a new {@code FeishuCardKind} in
 * {@link FeishuCardDispatcher#registerKinds()}.
 *
 * @param name           short human-readable label for logs
 * @param actionPrefix   matches the prefix of inbound
 *                       {@code action.value.action} (drives the inbound
 *                       {@code handle} dispatch). E.g.
 *                       {@code "tg_approval."} for tool-guard buttons.
 * @param renderer       converts a pending business object (e.g.
 *                       {@link vip.mate.channel.notification.ApprovalNotice})
 *                       into a Feishu interactive-card payload Map.
 *                       Throws {@link CardOversizedException} to signal
 *                       "this kind can't render now, fall back to text".
 * @param handler        processes an inbound {@code P2CardActionTrigger}
 *                       event for this kind.
 */
public record FeishuCardKind<T>(
        String name,
        String actionPrefix,
        Class<T> payloadType,
        FeishuCardRenderer<T> renderer,
        FeishuCardHandler handler
) {
    public FeishuCardKind {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("FeishuCardKind.name must not be blank");
        }
        if (actionPrefix == null || actionPrefix.isBlank()) {
            throw new IllegalArgumentException("FeishuCardKind.actionPrefix must not be blank");
        }
        if (payloadType == null || renderer == null || handler == null) {
            throw new IllegalArgumentException("FeishuCardKind payloadType/renderer/handler must not be null");
        }
    }

    /** Checked outbound dispatch without leaking an unsafe raw generic cast. */
    public java.util.Map<String, Object> renderPayload(Object payload) {
        if (!payloadType.isInstance(payload)) {
            throw new IllegalArgumentException("Card kind '" + name + "' expects "
                    + payloadType.getSimpleName() + " but received "
                    + (payload == null ? "null" : payload.getClass().getSimpleName()));
        }
        return renderer.render(payloadType.cast(payload));
    }
}

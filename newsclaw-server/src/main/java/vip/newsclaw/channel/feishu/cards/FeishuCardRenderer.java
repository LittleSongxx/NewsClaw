package vip.newsclaw.channel.feishu.cards;

import vip.newsclaw.channel.cards.CardOversizedException;
import java.util.Map;

/**
 * Build a Feishu interactive-card payload Map from a business object.
 *
 * <p>Implementations may throw {@link CardOversizedException} to signal
 * the caller to fall back to a non-card path (e.g. text approval
 * notice on the {@code AbstractChannelAdapter} default). Anything
 * else surfaces as a bug.
 *
 * @param <T> exact business payload accepted by this renderer. The enclosing
 *            {@link FeishuCardKind} carries the matching runtime class and
 *            performs a checked cast before invoking it.
 */
@FunctionalInterface
public interface FeishuCardRenderer<T> {
    /**
     * Build the Schema-2.0 interactive-card body Map ready to drop into
     * {@code im/v1/messages.create} with {@code msg_type=interactive}.
     */
    Map<String, Object> render(T payload) throws CardOversizedException;
}

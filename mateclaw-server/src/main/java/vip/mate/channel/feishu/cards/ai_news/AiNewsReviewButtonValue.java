package vip.mate.channel.feishu.cards.ai_news;

import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.channel.cards.CardOversizedException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Typed wire value for AI-news review buttons. Numeric IDs stay strings. */
public final class AiNewsReviewButtonValue {

    public static final String ACTION_PREFIX = "ai_news_review.";
    public static final int MAX_VALUE_BYTES = 2048;

    public enum Action {
        CONTINUE(ACTION_PREFIX + "continue"),
        VERIFY(ACTION_PREFIX + "verify"),
        CONFLICT(ACTION_PREFIX + "conflict"),
        DISMISS(ACTION_PREFIX + "dismiss"),
        START_RUN(ACTION_PREFIX + "start_run");

        private final String wireValue;

        Action(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        static Action fromWire(String value) {
            if (value == null) return null;
            for (Action action : values()) {
                if (action.wireValue.equals(value)) return action;
            }
            return null;
        }
    }

    public record Decoded(Action action, Long eventId, Long workspaceId, String requesterOpenId) {
    }

    private final ObjectMapper objectMapper;

    public AiNewsReviewButtonValue(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> encode(Action action, Long eventId, Long workspaceId,
                                      String requesterOpenId) {
        if (action == null || eventId == null || eventId <= 0
                || workspaceId == null || workspaceId <= 0
                || requesterOpenId == null || requesterOpenId.isBlank()) {
            throw new IllegalArgumentException("AI news review button requires action/event/workspace/requester");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("action", action.wireValue());
        value.put("eventId", String.valueOf(eventId));
        value.put("workspaceId", String.valueOf(workspaceId));
        value.put("requesterOpenId", requesterOpenId);
        try {
            int bytes = objectMapper.writeValueAsString(value)
                    .getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_VALUE_BYTES) {
                throw new CardOversizedException("ai_news_review button value exceeds "
                        + MAX_VALUE_BYTES + " bytes");
            }
        } catch (CardOversizedException e) {
            throw e;
        } catch (Exception e) {
            throw new CardOversizedException("failed to serialize ai_news_review button value");
        }
        return value;
    }

    public Decoded decode(Map<String, Object> value) {
        if (value == null) return null;
        Action action = Action.fromWire(string(value.get("action")));
        Long eventId = positiveLong(value.get("eventId"));
        Long workspaceId = positiveLong(value.get("workspaceId"));
        String requester = string(value.get("requesterOpenId"));
        if (action == null || eventId == null || workspaceId == null
                || requester == null || requester.isBlank()) return null;
        return new Decoded(action, eventId, workspaceId, requester);
    }

    private static Long positiveLong(Object value) {
        try {
            long parsed = Long.parseLong(string(value));
            return parsed > 0 ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}

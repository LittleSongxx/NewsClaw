package vip.mate.channel.feishu.cards.ai_news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiNewsReviewButtonValueTest {

    private final AiNewsReviewButtonValue codec = new AiNewsReviewButtonValue(new ObjectMapper());

    @Test
    void roundTripsIdsAsStringsWithoutJavascriptPrecisionLoss() {
        long eventId = 9_223_372_036_854_000_000L;
        Map<String, Object> wire = codec.encode(AiNewsReviewButtonValue.Action.VERIFY,
                eventId, 7L, "ou_requester");

        assertEquals(String.valueOf(eventId), wire.get("eventId"));
        AiNewsReviewButtonValue.Decoded decoded = codec.decode(wire);
        assertEquals(eventId, decoded.eventId());
        assertEquals(7L, decoded.workspaceId());
        assertEquals("ou_requester", decoded.requesterOpenId());
    }

    @Test
    void malformedOrIncompleteValuesFailClosed() {
        assertNull(codec.decode(Map.of("action", "ai_news_review.verify", "eventId", "1")));
        assertNull(codec.decode(Map.of("action", "unknown", "eventId", "1",
                "workspaceId", "7", "requesterOpenId", "ou_x")));
    }
}

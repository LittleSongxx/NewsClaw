package vip.newsclaw.channel.feishu.cards.ai_news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsReviewCardRendererTest {

    private final AiNewsReviewCardRenderer renderer = new AiNewsReviewCardRenderer(
            new AiNewsReviewButtonValue(new ObjectMapper()));

    @Test
    @SuppressWarnings("unchecked")
    void candidateHasReviewActionsButCannotStartTeamRun() {
        Map<String, Object> card = renderer.render(payload("candidate"));
        List<Map<String, Object>> elements = (List<Map<String, Object>>) card.get("elements");
        List<Map<String, Object>> actions = (List<Map<String, Object>>) elements.get(1).get("actions");
        List<String> wireActions = actions.stream()
                .map(button -> ((Map<String, Object>) button.get("value")).get("action").toString())
                .toList();

        assertEquals(4, actions.size());
        assertTrue(wireActions.contains(AiNewsReviewButtonValue.Action.VERIFY.wireValue()));
        assertFalse(wireActions.contains(AiNewsReviewButtonValue.Action.START_RUN.wireValue()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifiedOnlyOffersTeamRun() {
        Map<String, Object> card = renderer.render(payload("verified"));
        List<Map<String, Object>> elements = (List<Map<String, Object>>) card.get("elements");
        List<Map<String, Object>> actions = (List<Map<String, Object>>) elements.get(1).get("actions");
        Map<String, Object> value = (Map<String, Object>) actions.getFirst().get("value");

        assertEquals(1, actions.size());
        assertEquals(AiNewsReviewButtonValue.Action.START_RUN.wireValue(), value.get("action"));
        assertEquals("101", value.get("eventId"));
    }

    private static AiNewsReviewCardPayload payload(String status) {
        return new AiNewsReviewCardPayload(7L, "ou_requester", 101L,
                "OpenAI 发布模型", "摘要", "model", status, 0.82D,
                2, "verified".equals(status) ? 2 : 0, "official");
    }
}

package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the startup diagnostic that guards the inbound event boundary. */
class FeishuEventSubscriptionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void detectsMessageReceiveEvent() throws Exception {
        var root = objectMapper.readTree("""
                {"data":{"app_version":{"event_infos":[
                  {"event_name":"接收消息","event_type":"im.message.receive_v1"}
                ]}}}
                """);
        assertTrue(FeishuChannelAdapter.hasMessageEventSubscription(root));
    }

    @Test
    void rejectsCardOnlyCallbackList() throws Exception {
        var root = objectMapper.readTree("""
                {"data":{"app":{"callback_info":{"subscribed_callbacks":[
                  "card.action.trigger"
                ]}}}}
                """);
        assertFalse(FeishuChannelAdapter.hasMessageEventSubscription(root));
    }

    @Test
    void rejectsEventListWithoutMessageReceive() throws Exception {
        var root = objectMapper.readTree("""
                {"data":{"app_version":{"event_infos":[
                  {"event_name":"机器人进群","event_type":"im.chat.member.bot.added_v1"}
                ]}}}
                """);
        assertFalse(FeishuChannelAdapter.hasMessageEventSubscription(root));
    }

    @Test
    void acceptsLegacyApplicationEventShape() throws Exception {
        var root = objectMapper.readTree("""
                {"data":{"app":{"event":{"subscription_type":"websocket",
                  "subscribed_events":["im.message.receive_v1"]}}}}
                """);
        assertTrue(FeishuChannelAdapter.hasMessageEventSubscription(root));
    }

    @Test
    void missingMetadataFailsClosed() throws Exception {
        assertFalse(FeishuChannelAdapter.hasMessageEventSubscription(
                objectMapper.readTree("{\"code\":0}")));
        assertFalse(FeishuChannelAdapter.hasMessageEventSubscription(null));
    }
}

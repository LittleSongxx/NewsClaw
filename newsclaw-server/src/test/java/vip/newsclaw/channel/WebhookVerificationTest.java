package vip.newsclaw.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.newsclaw.channel.model.ChannelEntity;
import vip.newsclaw.channel.slack.SlackChannelAdapter;
import vip.newsclaw.channel.telegram.TelegramChannelAdapter;
import vip.newsclaw.channel.feishu.FeishuChannelAdapter;
import vip.newsclaw.channel.dingtalk.DingTalkChannelAdapter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebhookVerificationTest {

    @Test
    void slackRejectsBadSignatureAndAcceptsFreshValidSignature() throws Exception {
        ChannelEntity row = channel("slack", "{\"signing_secret\":\"secret\"}");
        SlackChannelAdapter adapter = new SlackChannelAdapter(
                row, mock(ChannelMessageRouter.class), new ObjectMapper());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"type\":\"event_callback\"}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "v0=" + HexFormat.of().formatHex(
                mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8)));

        assertTrue(adapter.acceptsWebhook(body, timestamp, signature));
        assertFalse(adapter.acceptsWebhook(body, timestamp, "v0=bad"));
    }

    @Test
    void telegramSecretIsStableAndRequired() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
                channel("telegram", "{\"bot_token\":\"123:token\"}"),
                mock(ChannelMessageRouter.class), new ObjectMapper());

        assertTrue(adapter.acceptsWebhookSecret(adapter.webhookSecret()));
        assertFalse(adapter.acceptsWebhookSecret("wrong"));
        assertFalse(adapter.acceptsWebhookSecret(null));
    }

    @Test
    void feishuPlaintextWebhookRequiresVerificationToken() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter(
                channel("feishu", "{\"app_id\":\"cli_1\",\"verification_token\":\"verify\"}"),
                mock(ChannelMessageRouter.class), new ObjectMapper());

        assertTrue(adapter.verifyAndDecodeWebhook(Map.of(
                "type", "url_verification", "token", "verify", "challenge", "ok")) != null);
        assertTrue(adapter.verifyAndDecodeWebhook(Map.of(
                "type", "url_verification", "token", "wrong", "challenge", "ok")) == null);
    }

    @Test
    void dingtalkRejectsStaleOrBadSignature() throws Exception {
        DingTalkChannelAdapter adapter = new DingTalkChannelAdapter(
                channel("dingtalk", "{\"client_secret\":\"secret\"}"),
                mock(ChannelMessageRouter.class), new ObjectMapper());
        String timestamp = String.valueOf(System.currentTimeMillis());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(
                mac.doFinal((timestamp + "\nsecret").getBytes(StandardCharsets.UTF_8)));

        assertTrue(adapter.acceptsWebhook(timestamp, signature));
        assertFalse(adapter.acceptsWebhook(timestamp, "bad"));
        assertFalse(adapter.acceptsWebhook(String.valueOf(System.currentTimeMillis() - 600_000), signature));
    }

    private static ChannelEntity channel(String type, String config) {
        ChannelEntity row = new ChannelEntity();
        row.setId(1L);
        row.setChannelType(type);
        row.setConfigJson(config);
        return row;
    }
}

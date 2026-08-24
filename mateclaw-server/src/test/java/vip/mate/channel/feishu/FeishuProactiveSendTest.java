package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Contract tests for cron-facing Feishu proactive delivery. */
class FeishuProactiveSendTest {

    @Test
    void non2xxResponseIsPropagatedToCronDelivery() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(403);
        when(response.body()).thenReturn("{\"code\":230013,\"msg\":\"no availability\"}");
        stubSend(client, response);

        FeishuChannelAdapter adapter = startedAdapter(client);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.proactiveSend("ou_test", "hello"));

        assertTrue(error.getMessage().contains("status=403"));
        assertTrue(error.getMessage().contains("230013"));
    }

    @Test
    void successfulResponseCompletesNormally() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"code\":0}");
        stubSend(client, response);

        FeishuChannelAdapter adapter = startedAdapter(client);
        assertDoesNotThrow(() -> adapter.proactiveSend("oc_test", "hello"));
    }

    @Test
    void networkFailureIsPropagatedToCronDelivery() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection refused"));

        FeishuChannelAdapter adapter = startedAdapter(client);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.proactiveSend("oc_test", "hello"));

        assertTrue(error.getMessage().contains("connection refused"));
    }

    @Test
    void stoppedChannelFailsInsteadOfReportingDeliverySuccess() {
        FeishuChannelAdapter adapter = newAdapter();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter.proactiveSend("oc_test", "hello"));
        assertTrue(error.getMessage().contains("not started"));
    }

    private static FeishuChannelAdapter startedAdapter(HttpClient client) throws Exception {
        FeishuChannelAdapter adapter = newAdapter();
        setField(adapter, "httpClient", client);
        setField(adapter, "tenantAccessToken", "test-token");
        setField(adapter, "tokenExpireTime", System.currentTimeMillis() + 60_000L);
        return adapter;
    }

    private static FeishuChannelAdapter newAdapter() {
        ChannelEntity entity = new ChannelEntity();
        entity.setId(1L);
        entity.setChannelType("feishu");
        entity.setConfigJson("{\"app_id\":\"test-app\",\"app_secret\":\"test-secret\"}");
        return new FeishuChannelAdapter(entity, mock(ChannelMessageRouter.class), new ObjectMapper());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubSend(HttpClient client, HttpResponse<String> response) throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = FeishuChannelAdapter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

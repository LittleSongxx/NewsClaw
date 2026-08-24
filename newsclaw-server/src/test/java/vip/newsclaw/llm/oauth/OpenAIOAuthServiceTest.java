package vip.newsclaw.llm.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.newsclaw.llm.repository.ModelProviderMapper;
import vip.newsclaw.llm.service.ModelProviderService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class OpenAIOAuthServiceTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("newsclaw.oauth.openai.callback-bind-host");
    }

    @Test
    void resolveCallbackBindHostDefaultsToLoopback() {
        OpenAIOAuthService service = service();

        assertEquals("127.0.0.1", service.resolveCallbackBindHost());
    }

    @Test
    void resolveCallbackBindHostUsesConfiguredProperty() {
        System.setProperty("newsclaw.oauth.openai.callback-bind-host", "0.0.0.0");
        OpenAIOAuthService service = service();

        assertEquals("0.0.0.0", service.resolveCallbackBindHost());
    }

    private OpenAIOAuthService service() {
        return new OpenAIOAuthService(mock(ModelProviderMapper.class), new ObjectMapper(),
                mock(ModelProviderService.class));
    }
}

package vip.newsclaw.news.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import vip.newsclaw.llm.model.ModelConfigEntity;
import vip.newsclaw.llm.service.ModelConfigService;
import vip.newsclaw.news.model.AiNewsModelRole;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiNewsModelRouterTest {

    private ModelConfigService modelConfigService;
    private Environment environment;
    private AiNewsModelRouter router;

    @BeforeEach
    void setUp() {
        modelConfigService = mock(ModelConfigService.class);
        environment = mock(Environment.class);
        router = new AiNewsModelRouter(modelConfigService, environment);
    }

    @Test
    void providerAndModelOverrideIsResolvedAndRecorded() {
        ModelConfigEntity model = model(11L, "openai", "gpt-5");
        when(environment.getProperty("newsclaw.ai-news.models.verification"))
                .thenReturn("openai/gpt-5");
        when(modelConfigService.findEnabledModel("openai", "gpt-5")).thenReturn(model);

        var route = router.route(AiNewsModelRole.VERIFICATION);

        assertTrue(route.available());
        assertTrue(route.configured());
        assertFalse(route.fallback());
        assertEquals("openai", route.provider());
        assertEquals("gpt-5", route.modelName());
        assertEquals(11L, route.modelId());
    }

    @Test
    void invalidOverrideFallsBackToEnabledModelWithReason() {
        ModelConfigEntity fallback = model(12L, "dashscope", "qwen-plus");
        when(environment.getProperty("newsclaw.ai-news.models.discovery"))
                .thenReturn("missing/no-such-model");
        when(modelConfigService.findEnabledModel("missing", "no-such-model")).thenReturn(null);
        when(modelConfigService.listEnabledModels()).thenReturn(List.of(fallback));

        var route = router.route(AiNewsModelRole.DISCOVERY);

        assertEquals("qwen-plus", route.modelName());
        assertTrue(route.fallback());
        assertFalse(route.configured());
        assertTrue(route.reason().contains("invalid role override"));
    }

    @Test
    void noEnabledModelProducesUnavailableRouteInsteadOfThrowing() {
        when(environment.getProperty("newsclaw.ai-news.models.delivery")).thenReturn(null);
        when(modelConfigService.listEnabledModels()).thenReturn(List.of());
        when(modelConfigService.getDefaultModel()).thenReturn(null);

        var route = router.route(AiNewsModelRole.DELIVERY);

        assertFalse(route.available());
        assertNull(route.provider());
        assertTrue(route.fallback());
        assertTrue(route.reason().contains("no enabled chat model"));
    }

    private static ModelConfigEntity model(long id, String provider, String name) {
        ModelConfigEntity value = new ModelConfigEntity();
        value.setId(id);
        value.setProvider(provider);
        value.setModelName(name);
        value.setEnabled(true);
        value.setMaxTokens(4096);
        value.setMaxInputTokens(32_000);
        return value;
    }
}

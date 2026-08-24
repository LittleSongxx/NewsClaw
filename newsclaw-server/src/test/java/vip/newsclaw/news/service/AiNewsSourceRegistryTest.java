package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsSourceRegistryTest {

    private final AiNewsSourceRegistry registry = new AiNewsSourceRegistry();

    @Test
    void classifiesOfficialDomainsAndOnlyRegisteredGithubOrganizations() {
        assertTrue(registry.isOfficialUrl("https://openai.com/index/model"));
        assertTrue(registry.isOfficialUrl("https://github.com/deepseek-ai/DeepSeek-V4"));
        assertFalse(registry.isOfficialUrl("https://github.com/unrelated/repository"));
        assertFalse(registry.isOfficialUrl("https://openai.com.example.net/fake"));
    }

    @Test
    void mapsMediaDomainsToIndependentPublisherKeys() {
        assertEquals("reuters", registry.trustedMediaSourceKey(
                "https://www.reuters.com/technology/story").orElseThrow());
        assertEquals("cls", registry.trustedMediaSourceKey(
                "https://www.cls.cn/detail/123").orElseThrow());
        assertFalse(registry.isTrustedMediaUrl("https://finance.example.net/story"));
    }
}

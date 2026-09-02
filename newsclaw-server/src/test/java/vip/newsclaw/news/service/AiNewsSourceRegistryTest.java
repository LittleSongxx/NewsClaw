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
        assertTrue(registry.isOfficialUrl("https://mistral.ai/synthetic/model"));
        assertTrue(registry.isOfficialUrl("https://github.com/deepseek-ai/DeepSeek-V4"));
        assertTrue(registry.isOfficialUrl("https://github.com/QwenLM/Qwen3"));
        assertTrue(registry.isOfficialUrl("https://ai.meta.com/research/model"));
        assertTrue(registry.isOfficialUrl("https://huggingface.co/blog/platform-update"));
        assertTrue(registry.isOfficialUrl("https://huggingface.co/huggingface/smollm"));
        assertTrue(registry.isOfficialUrl("https://www.qualcomm.com/developer/blog/update"));
        assertTrue(registry.isOfficialUrl("https://www.salesforce.com/news/press-releases/update"));
        assertTrue(registry.isOfficialUrl("https://aws.amazon.com/blogs/machine-learning/update"));
        assertTrue(registry.isOfficialUrl("https://radar.particle.pro/launch"));
        assertTrue(registry.isOfficialUrl(
                "https://yiyan.baidu.com/blog/zh/posts/ernie-model-release/"));
        assertTrue(registry.isOfficialUrl(
                "https://www.tencent.com/zh-cn/tencent-hunyuan-release/"));
        assertFalse(registry.isOfficialUrl("https://github.com/unrelated/repository"));
        assertFalse(registry.isOfficialUrl("https://huggingface.co/random-user/example-model"));
        assertFalse(registry.isOfficialUrl("https://huggingface.co/papers/2608.12345"));
        assertFalse(registry.isOfficialUrl("https://particle.news/story/aggregated-news"));
        assertFalse(registry.isOfficialUrl(
                "https://cloud.tencent.com/developer/news/2778384"));
        assertFalse(registry.isOfficialUrl(
                "https://cloud.baidu.com/article/521918"));
        assertFalse(registry.isOfficialUrl("https://openai.com.example.net/fake"));
    }

    @Test
    void mapsMediaDomainsToIndependentPublisherKeys() {
        assertEquals("reuters", registry.trustedMediaSourceKey(
                "https://www.reuters.com/technology/story").orElseThrow());
        assertEquals("cls", registry.trustedMediaSourceKey(
                "https://www.cls.cn/detail/123").orElseThrow());
        assertEquals("wired", registry.trustedMediaSourceKey(
                "https://www.wired.com/story/ai").orElseThrow());
        assertEquals("wall-street-journal", registry.trustedMediaSourceKey(
                "https://www.wsj.com/tech/ai").orElseThrow());
        assertEquals("fortune", registry.trustedMediaSourceKey(
                "https://fortune.com/2026/08/26/ai-story").orElseThrow());
        assertFalse(registry.isTrustedMediaUrl("https://finance.example.net/story"));
    }

    @Test
    void mapsOwnershipOnlyPublisherWithoutPromotingItsTrustTier() {
        String farmsArticle = "https://m.farms.com/news/farm-equipment/story.aspx";

        assertEquals("farms", registry.publisherSourceKey(farmsArticle).orElseThrow());
        assertFalse(registry.isOfficialUrl(farmsArticle));
        assertFalse(registry.isTrustedMediaUrl(farmsArticle));
        assertEquals("openai", registry.publisherSourceKey(
                "https://openai.com/index/model").orElseThrow());
        assertEquals("reuters", registry.publisherSourceKey(
                "https://www.reuters.com/technology/story").orElseThrow());
    }

    @Test
    void exposesRegistryOwnedOfficialSearchPlan() {
        var plan = registry.officialSearchPlan("product");

        assertTrue(plan.stream().anyMatch(item -> item.sourceKey().equals("salesforce")));
        assertTrue(plan.stream().anyMatch(item -> item.sourceKey().equals("bytedance")));
        assertTrue(registry.officialSearchDomains().contains("qualcomm.com"));
        assertTrue(registry.officialSearchDomains().contains("huggingface.co"));
        assertTrue(registry.officialSearchDomains().contains("www.tencent.com"));
        assertFalse(registry.officialSearchDomains().contains("tencent.com"));
        assertFalse(registry.officialSearchDomains().contains("cloud.baidu.com"));
        assertTrue(registry.trustedMediaSearchDomains().contains("techcrunch.com"));
        assertTrue(registry.trustedMediaSearchDomains("global").contains("techcrunch.com"));
        assertTrue(registry.trustedMediaSearchDomains("china").contains("jiqizhixin.com"));
        assertFalse(registry.trustedMediaSearchDomains("china").contains("techcrunch.com"));
    }
}

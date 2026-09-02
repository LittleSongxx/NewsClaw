package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsDiscoveryStoryDeduplicatorTest {

    private final AiNewsSourceRegistry registry = new AiNewsSourceRegistry();

    @Test
    void foldsBilingualAcquisitionCoverageButKeepsTwoIndependentPublishers() {
        var fortune = candidate("Nvidia nears $12.9 billion deal to buy Hugging Face",
                "https://fortune.com/2026/08/27/nvidia-hugging-face-deal", "");
        var krMobile = candidate("英伟达同意129亿美元买下 Hugging Face",
                "https://m.36kr.com/p/1", "");
        var krDesktop = candidate("129亿美元，老黄拿下全球最大AI开源平台 Hugging Face",
                "https://www.36kr.com/p/2", "");
        var cls = candidate("据称英伟达129亿美元收购 Hugging Face",
                "https://www.cls.cn/detail/3", "");

        var output = AiNewsDiscoveryStoryDeduplicator.deduplicate(
                List.of(fortune, krMobile, krDesktop, cls), registry, 2);

        assertEquals(List.of(fortune, krMobile), output.candidates());
        assertEquals(1, output.provisionalStoryCount());
        assertEquals(1, output.samePublisherDuplicates());
        assertEquals(1, output.sourceQuotaDuplicates());
    }

    @Test
    void matchesProductUpdatesAndSecurityCoverageAcrossDifferentHeadlines() {
        assertTrue(AiNewsDiscoveryStoryDeduplicator.sameStory(
                candidate("3 new ways to plan and book travel in Search",
                        "https://blog.google/products/search/travel", "AI Mode can book hotels"),
                candidate("Google's AI Mode can now track flight prices and book hotels",
                        "https://techcrunch.com/google-ai-mode-travel", ""), registry));
        assertTrue(AiNewsDiscoveryStoryDeduplicator.sameStory(
                candidate("OpenAI, Anthropic, Google call for action against rogue AI",
                        "https://techcrunch.com/rogue-ai", "cyber threats and hacks"),
                candidate("Major tech companies call for defensive surge to defeat AI hacks",
                        "https://reuters.com/defensive-surge", "OpenAI and Anthropic signed the letter"),
                registry));
        assertTrue(AiNewsDiscoveryStoryDeduplicator.sameStory(
                candidate("Hugging Face is selling the open source robot Microduck",
                        "https://techcrunch.com/microduck", ""),
                candidate("抱抱脸推出消费级开源机器鸭",
                        "https://www.cls.cn/detail/microduck", "Microduck 可用强化学习训练"),
                registry));
    }

    @Test
    void doesNotMergeDifferentActionsFromTheSameVendor() {
        assertFalse(AiNewsDiscoveryStoryDeduplicator.sameStory(
                candidate("Anthropic launches a standard for machine-operating agents",
                        "https://cnbc.com/anthropic-standard", ""),
                candidate("Anthropic abandoned a $7 billion purchase of MatX",
                        "https://reuters.com/anthropic-matx", ""), registry));
        assertFalse(AiNewsDiscoveryStoryDeduplicator.sameStory(
                candidate("Nvidia releases new robotics models",
                        "https://nvidia.com/robotics-models", ""),
                candidate("Nvidia rises after signaling longer AI spending runway",
                        "https://reuters.com/nvidia-spending", ""), registry));
    }

    private static AiNewsDiscoverySearchService.DiscoveryCandidate candidate(
            String title, String url, String snippet) {
        return new AiNewsDiscoverySearchService.DiscoveryCandidate(0, title, url, "source",
                "2026-08-27T10:00:00Z", 0.1D, 0.8D, false, true,
                List.of("test"), snippet,
                AiNewsDiscoverySearchService.TemporalStatus.IN_WINDOW, "test", null);
    }
}

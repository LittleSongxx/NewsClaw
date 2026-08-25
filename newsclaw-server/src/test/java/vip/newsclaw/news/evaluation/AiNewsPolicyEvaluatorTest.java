package vip.newsclaw.news.evaluation;

import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsPolicyEvaluatorTest {

    private final AiNewsPolicyEvaluator evaluator =
            new AiNewsPolicyEvaluator(new AiNewsSourceRegistry());

    @Test
    void verificationRequiresOfficialOrTwoIndependentMediaSources() {
        var official = evidence("https://openai.com/index/release");
        var twoMedia = new AiNewsPolicyEvaluator.EvidenceCase(
                "media", "verified", "media", "https://www.reuters.com/story",
                List.of("https://www.reuters.com/story", "https://www.36kr.com/p/story"),
                List.of(), false, "company released a model", "company released a model",
                List.of(), List.of(), true);
        var community = evidence("https://forum.example.org/post");

        assertTrue(evaluator.verificationGate(official));
        assertTrue(evaluator.verificationGate(twoMedia));
        assertFalse(evaluator.verificationGate(community));
    }

    @Test
    void canonicalUrlDeduplicationRemovesFragmentsAndRepeatedSlashes() {
        var distinct = AiNewsPolicyEvaluator.canonicalDistinct(List.of(
                "https://openai.com//index/release/#top",
                "https://openai.com/index/release/"));

        assertEquals(1, distinct.size());
        assertTrue(AiNewsPolicyEvaluator.citationAllowed(
                List.of("https://openai.com/index/release/#section"),
                List.of("https://openai.com/index/release/")));
    }

    private static AiNewsPolicyEvaluator.EvidenceCase evidence(String url) {
        return new AiNewsPolicyEvaluator.EvidenceCase(
                "case", "verified", url.contains("openai") ? "official" : "community", url,
                List.of(url), List.of(), false, "the release is public", "the release is public",
                List.of(url), List.of(url), true);
    }
}

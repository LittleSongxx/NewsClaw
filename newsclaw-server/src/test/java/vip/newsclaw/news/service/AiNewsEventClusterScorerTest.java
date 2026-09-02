package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsEventClusterScorerTest {

    private final AiNewsEventClusteringProperties properties =
            new AiNewsEventClusteringProperties();
    private final AiNewsEventClusterScorer scorer = new AiNewsEventClusterScorer(properties);

    @Test
    void exactCanonicalUrlLinksWithinHorizonButNotAcrossRollingPageHorizon() {
        var first = event(1L, "a", "Release notes for Model X", "model", Set.of("Vendor", "Model X"),
                Set.of("https://example.com/news/model-x?utm_source=test"), at(0));
        var samePage = event(2L, "b", "Model X is now available", "model",
                Set.of("Vendor", "Model X"), Set.of("https://example.com/news/model-x"), at(3));
        var rollingPageLater = event(3L, "c", "A different monthly update", "model",
                Set.of("Vendor", "Model Y"), Set.of("https://example.com/news/model-x"),
                at(24 * 20));

        assertTrue(scorer.score(samePage, first).automaticLink());
        assertEquals("EXACT_URL", scorer.score(samePage, first).assignmentOrigin());
        assertFalse(scorer.score(rollingPageLater, first).automaticLink());
        assertEquals(0.0D, scorer.score(rollingPageLater, first).value());
    }

    @Test
    void strongCrossLanguageEntityAndActionSignalsCanLink() {
        var english = event(1L, "a", "OpenAI releases GPT-6 reasoning model", "model",
                Set.of("OpenAI", "GPT-6"), Set.of(), at(0));
        var chinese = event(2L, "b", "OpenAI 发布 GPT-6 推理模型", "model",
                Set.of("OpenAI", "GPT-6"), Set.of(), at(2));

        AiNewsEventClusterScorer.Score score = scorer.score(chinese, english);

        assertTrue(score.automaticLink());
        assertTrue(score.value() >= properties.getAutoLinkThreshold());
        assertEquals(64, scorer.configHash().length());
    }

    @Test
    void sameVendorDifferentProductsAreReviewedInsteadOfAutoMerged() {
        var model = event(1L, "a", "OpenAI releases GPT-6 reasoning model", "model",
                Set.of("OpenAI", "GPT-6"), Set.of(), at(0));
        var video = event(2L, "b", "OpenAI releases Sora 3 video model", "model",
                Set.of("OpenAI", "Sora 3"), Set.of(), at(1));

        AiNewsEventClusterScorer.Score score = scorer.score(video, model);

        assertFalse(score.automaticLink());
        assertTrue(score.reviewSuggested());
    }

    @Test
    void unrelatedSameDayStoriesDoNotEnterReviewQueue() {
        var model = event(1L, "a", "OpenAI releases GPT-6 reasoning model", "model",
                Set.of("OpenAI", "GPT-6"), Set.of(), at(0));
        var funding = event(2L, "b", "Robotics startup Atlas raises Series B", "funding",
                Set.of("Atlas Robotics"), Set.of(), at(1));

        AiNewsEventClusterScorer.Score score = scorer.score(funding, model);

        assertFalse(score.automaticLink());
        assertFalse(score.reviewSuggested());
        assertEquals(0.0D, score.value());
    }

    private static AiNewsEventClusterScorer.EventDocument event(
            Long id, String key, String title, String category, Set<String> entities,
            Set<String> urls, LocalDateTime time) {
        return new AiNewsEventClusterScorer.EventDocument(id, key, title, title, category,
                entities, urls, time, time);
    }

    private static LocalDateTime at(int hours) {
        return LocalDateTime.of(2026, 8, 27, 0, 0).plusHours(hours);
    }
}

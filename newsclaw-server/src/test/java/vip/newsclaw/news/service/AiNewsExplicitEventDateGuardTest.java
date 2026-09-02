package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;
import vip.newsclaw.exception.NewsClawException;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsExplicitEventDateGuardTest {

    private static final Instant START = Instant.parse("2026-08-26T03:15:40Z");
    private static final Instant END = Instant.parse("2026-08-27T03:15:40Z");

    @Test
    void rejectsFreshArticleWhoseQuotedLaunchActionIsExplicitlyOld() {
        NewsClawException error = assertThrows(NewsClawException.class,
                () -> AiNewsExplicitEventDateGuard.validate(
                        "On August 25, the company launched a project called Index.",
                        LocalDateTime.of(2026, 8, 26, 13, 35), START, END));

        assertTrue(error.getMessage().contains("2026-08-25"));
        assertTrue(error.getMessage().contains("网页发布时间不能替代"));
    }

    @Test
    void allowsBoundaryDayAndDoesNotTreatUnrelatedHistoricalContextAsEventTime() {
        assertDoesNotThrow(() -> AiNewsExplicitEventDateGuard.validate(
                "On August 26, the company released Model X.",
                LocalDateTime.of(2026, 8, 26, 13, 35), START, END));
        assertDoesNotThrow(() -> AiNewsExplicitEventDateGuard.validate(
                "The prior round closed on August 25. The company now has 20 employees.",
                LocalDateTime.of(2026, 8, 26, 13, 35), START, END));
    }

    @Test
    void rejectsTheExclusiveEndDayWhenWindowEndsAtUtcMidnight() {
        assertThrows(NewsClawException.class,
                () -> AiNewsExplicitEventDateGuard.validate(
                        "On August 27, the company launched Model Y.",
                        LocalDateTime.of(2026, 8, 27, 1, 0),
                        Instant.parse("2026-08-26T00:00:00Z"),
                        Instant.parse("2026-08-27T00:00:00Z")));
    }
}

package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiNewsAtomicFactGuardTest {

    @Test
    void derivesEveryCardFactFieldFromTheEvidenceBoundClaim() {
        var fact = AiNewsAtomicFactGuard.prepare("financing", List.of(" Instinct "),
                "Instinct raised $250 million in a Series B round.",
                Instant.parse("2026-08-26T03:15:40Z"));

        assertEquals(fact.title(), fact.summary());
        assertEquals("funding", fact.category());
        assertEquals(List.of("Instinct"), fact.entities());
    }

    @Test
    void fingerprintsExactCrossSourceClaimsTogetherButSeparatesWindows() {
        var first = AiNewsAtomicFactGuard.prepare("funding", List.of("Instinct"),
                "Instinct raised $250 million.", Instant.parse("2026-08-26T00:00:00Z"));
        var same = AiNewsAtomicFactGuard.prepare("funding", List.of("instinct"),
                "Instinct raised $250 million!", Instant.parse("2026-08-26T23:59:59Z"));
        var nextDay = AiNewsAtomicFactGuard.prepare("funding", List.of("Instinct"),
                "Instinct raised $250 million.", Instant.parse("2026-08-27T00:00:00Z"));

        assertEquals(first.eventKeyMaterial(), same.eventKeyMaterial());
        assertNotEquals(first.eventKeyMaterial(), nextDay.eventKeyMaterial());
    }

    @Test
    void rejectsCompoundCardSizedProse() {
        assertThrows(IllegalArgumentException.class, () -> AiNewsAtomicFactGuard.prepare(
                "product", List.of(), "x".repeat(513), Instant.now()));
    }
}

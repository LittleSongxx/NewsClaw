package vip.newsclaw.tool.search;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TavilyApiKeyPoolTest {

    @Test
    void parsesMultipleSeparatorsAndDeduplicatesWithoutChangingOrder() {
        assertEquals(List.of("key-a", "key-b", "key-c", "key-d"),
                TavilyApiKeyPool.parseConfiguredKeys(
                        " key-a\nkey-b, key-a ; key-c\tkey-d "));
    }

    @Test
    void keepsSuccessfulKeyStickyAndMovesAfterQuotaFailure() {
        TavilyApiKeyPool pool = new TavilyApiKeyPool();
        String configured = "key-a,key-b,key-c";

        TavilyApiKeyPool.Lease first = pool.acquire(configured, Set.of());
        assertEquals(0, first.index());
        pool.markSuccess(first);
        assertEquals(0, pool.acquire(configured, Set.of()).index());

        pool.markFailure(first, TavilyApiKeyPool.FailureKind.QUOTA_LIMIT, null);
        assertEquals(1, pool.acquire(configured, Set.of()).index());
    }

    @Test
    void honorsRateLimitCooldownAndMakesKeyEligibleAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T00:00:00Z"));
        TavilyApiKeyPool pool = new TavilyApiKeyPool(
                clock, Duration.ofMinutes(1), Duration.ofHours(1));
        String configured = "key-a,key-b";

        TavilyApiKeyPool.Lease first = pool.acquire(configured, Set.of());
        pool.markFailure(first, TavilyApiKeyPool.FailureKind.RATE_LIMIT, Duration.ofSeconds(5));
        TavilyApiKeyPool.Lease second = pool.acquire(configured, Set.of());
        assertEquals(1, second.index());
        pool.markFailure(second, TavilyApiKeyPool.FailureKind.AUTHENTICATION, null);

        assertThrows(IllegalStateException.class, () -> pool.acquire(configured, Set.of()));
        clock.advance(Duration.ofSeconds(6));
        assertEquals(0, pool.acquire(configured, Set.of()).index());
    }

    @Test
    void invalidKeyStaysDisabledUntilConfigurationChanges() {
        TavilyApiKeyPool pool = new TavilyApiKeyPool();
        TavilyApiKeyPool.Lease lease = pool.acquire("key-a", Set.of());
        pool.markFailure(lease, TavilyApiKeyPool.FailureKind.AUTHENTICATION, null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> pool.acquire("key-a", Set.of()));
        assertFalse(error.getMessage().contains("key-a"));

        assertEquals(0, pool.acquire("key-a,key-b", Set.of()).index());
    }

    @Test
    void staleConcurrentSuccessCannotResurrectAQuotaLimitedKey() {
        TavilyApiKeyPool pool = new TavilyApiKeyPool();
        String configured = "key-a,key-b";
        TavilyApiKeyPool.Lease inFlightOne = pool.acquire(configured, Set.of());
        TavilyApiKeyPool.Lease inFlightTwo = pool.acquire(configured, Set.of());

        pool.markFailure(inFlightOne, TavilyApiKeyPool.FailureKind.QUOTA_LIMIT, null);
        pool.markSuccess(inFlightTwo);

        assertEquals(1, pool.acquire(configured, Set.of()).index());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

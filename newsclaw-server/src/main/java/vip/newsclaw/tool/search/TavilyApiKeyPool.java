package vip.newsclaw.tool.search;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Thread-safe, sticky failover pool for Tavily API keys.
 *
 * <p>The first healthy key remains active until Tavily explicitly reports an
 * authentication, rate-limit, or quota failure. This avoids consuming every
 * account on transient network/server errors while still moving to the next
 * key immediately when the current key cannot serve requests.</p>
 *
 * <p>Key material is intentionally never exposed by this type's exceptions or
 * diagnostics. Callers should identify a lease only by its one-based slot.</p>
 */
public final class TavilyApiKeyPool {

    static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofSeconds(60);
    static final Duration DEFAULT_QUOTA_COOLDOWN = Duration.ofHours(6);

    enum FailureKind {
        AUTHENTICATION,
        RATE_LIMIT,
        QUOTA_LIMIT
    }

    record Lease(long generation, long entryVersion, int index, String apiKey, int total) {
        int slot() {
            return index + 1;
        }
    }

    private static final class Entry {
        private final String apiKey;
        private Instant availableAt = Instant.MIN;
        private boolean disabled;
        private long stateVersion;

        private Entry(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    private final Clock clock;
    private final Duration rateLimitCooldown;
    private final Duration quotaCooldown;

    private List<String> configuredKeys = List.of();
    private List<Entry> entries = List.of();
    private long generation;
    private int activeIndex;

    TavilyApiKeyPool() {
        this(Clock.systemUTC(), DEFAULT_RATE_LIMIT_COOLDOWN, DEFAULT_QUOTA_COOLDOWN);
    }

    TavilyApiKeyPool(Clock clock, Duration rateLimitCooldown, Duration quotaCooldown) {
        this.clock = clock;
        this.rateLimitCooldown = requirePositive(rateLimitCooldown, "rateLimitCooldown");
        this.quotaCooldown = requirePositive(quotaCooldown, "quotaCooldown");
    }

    public static List<String> parseConfiguredKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String token : raw.split("[\\s,;]+")) {
            if (token != null && !token.isBlank()) {
                unique.add(token.trim());
            }
        }
        return List.copyOf(unique);
    }

    public static int configuredKeyCount(String raw) {
        return parseConfiguredKeys(raw).size();
    }

    synchronized Lease acquire(String raw, Set<Integer> attemptedIndexes) {
        configure(raw);
        if (entries.isEmpty()) {
            throw new IllegalStateException("No Tavily API key is configured");
        }

        Set<Integer> attempted = attemptedIndexes == null ? Set.of() : attemptedIndexes;
        Instant now = clock.instant();
        for (int offset = 0; offset < entries.size(); offset++) {
            int index = Math.floorMod(activeIndex + offset, entries.size());
            Entry entry = entries.get(index);
            if (!attempted.contains(index) && !entry.disabled && !entry.availableAt.isAfter(now)) {
                return new Lease(generation, entry.stateVersion, index, entry.apiKey, entries.size());
            }
        }

        throw new IllegalStateException("All configured Tavily API keys are unavailable or cooling down");
    }

    synchronized void markSuccess(Lease lease) {
        Entry entry = currentEntry(lease);
        if (entry == null) {
            return;
        }
        // A request can succeed after a concurrent request has already rate
        // limited or exhausted the same key. Such a stale success must not
        // resurrect the failed key.
        if (lease.entryVersion() != entry.stateVersion) {
            return;
        }
        entry.availableAt = Instant.MIN;
        activeIndex = lease.index();
    }

    synchronized void markFailure(Lease lease, FailureKind failureKind, Duration retryAfter) {
        Entry entry = currentEntry(lease);
        if (entry == null) {
            return;
        }

        Instant now = clock.instant();
        switch (failureKind) {
            case AUTHENTICATION -> entry.disabled = true;
            case RATE_LIMIT -> entry.availableAt = laterOf(entry.availableAt,
                    now.plus(normalizeCooldown(retryAfter, rateLimitCooldown)));
            case QUOTA_LIMIT -> entry.availableAt = laterOf(entry.availableAt, now.plus(quotaCooldown));
        }
        entry.stateVersion++;
        activeIndex = (lease.index() + 1) % entries.size();
    }

    synchronized int size(String raw) {
        configure(raw);
        return entries.size();
    }

    private void configure(String raw) {
        List<String> parsed = parseConfiguredKeys(raw);
        if (parsed.equals(configuredKeys)) {
            return;
        }

        configuredKeys = parsed;
        List<Entry> replacement = new ArrayList<>(parsed.size());
        for (String key : parsed) {
            replacement.add(new Entry(key));
        }
        entries = replacement;
        activeIndex = 0;
        generation++;
    }

    private Entry currentEntry(Lease lease) {
        if (lease == null || lease.generation() != generation
                || lease.index() < 0 || lease.index() >= entries.size()) {
            return null;
        }
        Entry entry = entries.get(lease.index());
        return entry.apiKey.equals(lease.apiKey()) ? entry : null;
    }

    private Duration normalizeCooldown(Duration requested, Duration fallback) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return fallback;
        }
        return requested;
    }

    private Instant laterOf(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}

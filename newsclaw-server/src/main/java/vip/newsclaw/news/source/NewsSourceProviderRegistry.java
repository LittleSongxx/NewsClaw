package vip.newsclaw.news.source;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;

/** Spring registry and failure-isolating facade for source providers. */
@Slf4j
@Component
public class NewsSourceProviderRegistry {

    private final Map<String, NewsSourceProvider> providers;

    public NewsSourceProviderRegistry(List<NewsSourceProvider> discovered) {
        Map<String, NewsSourceProvider> index = new LinkedHashMap<>();
        if (discovered != null) {
            for (NewsSourceProvider provider : discovered) {
                if (provider == null || provider.providerId() == null
                        || provider.providerId().isBlank()) continue;
                String key = key(provider.providerId());
                NewsSourceProvider previous = index.putIfAbsent(key, provider);
                if (previous != null) {
                    throw new IllegalStateException("duplicate news source provider: "
                            + provider.providerId());
                }
            }
        }
        // Keep discovery order: provider ordering is also the deterministic
        // tie-breaker when two sources return the same canonical URL (the
        // deployment can put an official adapter before a media adapter).
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(index));
    }

    public List<NewsSourceProvider> all() {
        return List.copyOf(providers.values());
    }

    public Optional<NewsSourceProvider> find(String providerId) {
        return Optional.ofNullable(providerId == null ? null : providers.get(key(providerId)));
    }

    public List<NewsSourceResult> search(NewsSourceQuery query, List<String> providerIds) {
        int limit = query == null ? 10 : query.limit();
        if (limit <= 0) return List.of();

        List<NewsSourceProvider> selected;
        if (providerIds == null || providerIds.isEmpty()) {
            selected = all();
        } else {
            // Ignore unknown ids, but do not call a selected provider twice if
            // the model repeats an id in its comma-separated argument.
            Set<String> requested = providerIds.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(NewsSourceProviderRegistry::key)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            selected = requested.stream().map(providers::get)
                    .filter(java.util.Objects::nonNull).toList();
        }

        // Query every selected provider before applying the aggregate limit.
        // A provider must not be able to fill the whole page and starve later
        // source families; the round-robin merge below keeps the result
        // deterministic while preserving first-provider precedence on URL
        // collisions.
        List<List<NewsSourceResult>> providerResults = new java.util.ArrayList<>();
        int anonymousResult = 0;
        for (NewsSourceProvider provider : selected) {
            try {
                List<NewsSourceResult> rows = provider.search(query);
                providerResults.add(rows == null ? List.of() : rows);
            } catch (Exception e) {
                log.warn("News source provider {} search failed: {}", provider.providerId(), e.getMessage());
                providerResults.add(List.of());
            }
        }
        Map<String, NewsSourceResult> unique = new LinkedHashMap<>();
        int depth = providerResults.stream().mapToInt(List::size).max().orElse(0);
        for (int index = 0; index < depth && unique.size() < limit; index++) {
            for (List<NewsSourceResult> rows : providerResults) {
                if (index >= rows.size()) continue;
                NewsSourceResult result = rows.get(index);
                // A provider is an extension point. Be defensive about a
                // malformed implementation so one bad row cannot break the
                // whole discovery turn.
                if (result == null || result.provenance() == null) continue;
                String key = canonicalKey(result);
                if (key == null) {
                    // Keep a provenance-bearing result even when an adapter
                    // omitted both URL fields; it cannot collide with a
                    // canonical URL from another result.
                    key = "__anonymous__" + anonymousResult++;
                }
                unique.putIfAbsent(key, result);
                if (unique.size() >= limit) break;
            }
        }
        return List.copyOf(unique.values());
    }

    private static String canonicalKey(NewsSourceResult result) {
        String canonical = result.canonicalUrl();
        if (canonical == null || canonical.isBlank()) canonical = result.sourceUrl();
        if (canonical == null || canonical.isBlank()) return null;
        return canonical.trim();
    }

    public Optional<NewsSourceResult> fetch(String providerId, URI url) {
        NewsSourceProvider provider = providerId == null ? null : providers.get(key(providerId));
        if (provider == null) return Optional.empty();
        try {
            return provider.fetch(url);
        } catch (Exception e) {
            log.warn("News source provider {} fetch failed: {}", providerId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String key(String providerId) {
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}

package vip.newsclaw.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.newsclaw.system.model.SystemSettingsDTO;
import vip.newsclaw.system.service.SystemSettingService;
import vip.newsclaw.tool.search.SearchCache;
import vip.newsclaw.tool.search.SearchProvider;
import vip.newsclaw.tool.search.SearchProviderRegistry;
import vip.newsclaw.tool.search.SearchProviderRegistry.ResolvedProvider;
import vip.newsclaw.tool.search.SearchQuery;
import vip.newsclaw.tool.search.SearchResult;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 搜索服务：通过 {@link SearchProviderRegistry} 实现 provider chain 路由与 keyless fallback
 *
 * <p>Phase 2 增强：
 * <ul>
 *   <li>支持 {@link SearchQuery} 高级参数（freshness / language / count）</li>
 *   <li>内存缓存（15 分钟 TTL，避免重复调用 API）</li>
 *   <li>搜索结果安全包装（防止 prompt injection）</li>
 * </ul>
 *
 * @author NewsClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchService {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Keep the complete structured candidate envelope below the spill threshold. */
    static final int MAX_FORMATTED_CHARS = 7_000;
    private static final int MAX_SNIPPET_CHARS = 480;
    /** Hard safety bound for an explicitly requested provider union. */
    static final int MAX_UNION_PROVIDERS = 8;

    private final SystemSettingService systemSettingService;
    private final SearchProviderRegistry providerRegistry;
    private final SearchCache searchCache;

    /**
     * 执行搜索（裸 query，向后兼容）
     */
    public String search(String query) {
        return search(SearchQuery.of(query));
    }

    /**
     * 执行搜索（支持 freshness / language / count 等高级参数）
     */
    public String search(SearchQuery searchQuery) {
        SearchBatch batch = searchCandidates(searchQuery);
        // Preserve the legacy plain-text error for a genuine provider failure,
        // but keep a structured zero-row response when the provider returned
        // rows that were removed by the common URL/domain contract.
        if (batch.results().isEmpty() && batch.suppliedResultCount() == 0) {
            return batch.message();
        }
        return formatResults(batch);
    }

    /**
     * Structured, internal discovery boundary used by vertical retrievers.
     * The returned rows remain candidate hints and deliberately carry no
     * evidence/verification flag.
     */
    public SearchBatch searchCandidates(SearchQuery searchQuery) {
        SystemSettingsDTO config = systemSettingService.getSearchSettings();
        SearchQuery query = searchQuery == null ? SearchQuery.of("") : searchQuery;
        List<ProviderFailure> failures = new ArrayList<>();

        if (config == null || !Boolean.TRUE.equals(config.getSearchEnabled())) {
            return SearchBatch.unavailable("搜索功能已关闭，请在系统设置中启用。",
                    List.of(new ProviderFailure("search", "search disabled")));
        }

        // 通过 provider registry 解析最佳 provider。Registry extensions are
        // deployment code too; surface a resolution failure as structured
        // diagnostics instead of allowing it to disappear behind a generic
        // "search unavailable" response.
        ResolvedProvider resolved;
        try {
            resolved = providerRegistry.resolve(config);
        } catch (Exception error) {
            String message = safeFailure(error);
            failures.add(new ProviderFailure("registry", message));
            return SearchBatch.unavailable(
                    "搜索 provider 解析失败: " + message, failures);
        }
        log.info("搜索 provider 解析: {}", resolved != null
                ? resolved.provider().id() + " (source=" + resolved.source() + ")"
                : "无可用 provider");

        if (resolved != null) {
            ProviderAttempt attempt = tryProvider(resolved.provider(), query, config);
            if (attempt.batch() != null && !attempt.batch().results().isEmpty()) {
                log.info("搜索成功 (provider={}, source={})", resolved.provider().id(), resolved.source());
                return attempt.batch().withFailures(failures);
            }
            failures.add(attempt.failure());
        }

        // 首选 provider 失败或不可用，遍历 fallback chain
        if (Boolean.TRUE.equals(config.getSearchFallbackEnabled()) || resolved == null) {
            for (SearchProvider p : providerRegistry.allSorted()) {
                if (resolved != null && p.id().equalsIgnoreCase(resolved.provider().id())) continue;
                boolean available;
                try {
                    available = p.isAvailable(config);
                } catch (Exception error) {
                    available = false;
                    failures.add(new ProviderFailure(p.id(), safeFailure(error)));
                }
                if (!available) {
                    log.debug("搜索 fallback 跳过不可用 provider={}", p.id());
                    if (failures.stream().noneMatch(item -> item.providerId().equals(p.id()))) {
                        failures.add(new ProviderFailure(p.id(), "provider unavailable"));
                    }
                    continue;
                }

                log.info("搜索 fallback 尝试 provider={}", p.id());

                ProviderAttempt attempt = tryProvider(p, query, config);
                if (attempt.batch() != null && !attempt.batch().results().isEmpty()) {
                    log.info("搜索 fallback 成功 (provider={})", p.id());
                    return attempt.batch().withFailures(failures);
                }
                failures.add(attempt.failure());
            }
        }

        return SearchBatch.unavailable(
                "搜索暂时不可用。建议在系统设置中配置 Serper 或 Tavily API Key 以获得更好的搜索体验。",
                failures);
    }

    /**
     * Search one explicitly selected provider.  News verticals use this
     * boundary to build a real multi-provider union; it never silently
     * resolves to a different provider or invokes the fallback chain.
     */
    public SearchBatch searchCandidates(String providerId, SearchQuery searchQuery) {
        String requested = providerId == null ? "" : providerId.trim();
        SearchQuery query = searchQuery == null ? SearchQuery.of("") : searchQuery;
        SystemSettingsDTO config = systemSettingService.getSearchSettings();
        return searchExplicitProvider(requested, query, config);
    }

    private SearchBatch searchExplicitProvider(String requested,
                                               SearchQuery query,
                                               SystemSettingsDTO config) {
        if (config == null || !Boolean.TRUE.equals(config.getSearchEnabled())) {
            return SearchBatch.unavailable(requested,
                    "搜索功能已关闭，请在系统设置中启用。",
                    List.of(new ProviderFailure(requested, "search disabled")));
        }
        if (requested.isBlank()) {
            return SearchBatch.unavailable("providerId 不能为空",
                    List.of(new ProviderFailure("", "provider id is blank")));
        }
        SearchProvider provider = providerRegistry.getById(requested);
        if (provider == null) {
            return SearchBatch.unavailable(requested, "未找到搜索 provider: " + requested,
                    List.of(new ProviderFailure(requested, "provider not found")));
        }
        try {
            if (!provider.isAvailable(config)) {
                return SearchBatch.unavailable(requested,
                        "搜索 provider 不可用: " + requested,
                        List.of(new ProviderFailure(requested, "provider unavailable")));
            }
        } catch (Exception error) {
            String message = safeFailure(error);
            return SearchBatch.unavailable(requested, "搜索 provider 检查失败: " + message,
                    List.of(new ProviderFailure(requested, message)));
        }
        ProviderAttempt attempt = tryProvider(provider, query, config);
        if (attempt.batch() != null) {
            return attempt.failure() == null ? attempt.batch()
                    : attempt.batch().withFailures(List.of(attempt.failure()));
        }
        ProviderFailure failure = attempt.failure();
        return SearchBatch.unavailable(requested,
                "搜索 provider " + requested + " 失败: " + failure.message(),
                List.of(failure));
    }

    /** Parameter-order convenience overload for callers that already hold a query. */
    public SearchBatch searchCandidates(SearchQuery searchQuery, String providerId) {
        return searchCandidates(providerId, searchQuery);
    }

    /**
     * Search an explicit, ordered provider set and retain every provider's
     * observations. This is deliberately separate from the normal
     * auto-detect/fallback API: a news scan needs marginal recall from more
     * than one index, while general chat search should keep its old
     * first-success semantics.
     *
     * <p>Each requested provider is attempted independently (without
     * silently substituting another provider). A failed provider contributes a
     * structured {@link ProviderFailure}; successful rows retain the provider
     * id in both their batch and row provenance. URL/story de-duplication is
     * intentionally left to the vertical discovery layer, which has the
     * canonicalisation and source-independence policy needed to do that
     * safely.</p>
     */
    public SearchUnion searchCandidates(List<String> providerIds, SearchQuery searchQuery) {
        SearchQuery query = searchQuery == null ? SearchQuery.of("") : searchQuery;
        List<String> requested = normalizeProviderIds(providerIds);
        List<ProviderFailure> failures = new ArrayList<>();
        if (requested.isEmpty()) {
            failures.add(new ProviderFailure("union", "provider list is empty"));
            return new SearchUnion(List.of(), List.of(), List.of(), 0, 0, failures);
        }
        if (requested.size() > MAX_UNION_PROVIDERS) {
            int omitted = requested.size() - MAX_UNION_PROVIDERS;
            failures.add(new ProviderFailure("union",
                    "provider list truncated to " + MAX_UNION_PROVIDERS
                            + " (" + omitted + " omitted)"));
            requested = List.copyOf(requested.subList(0, MAX_UNION_PROVIDERS));
        }

        List<SearchBatch> batches = new ArrayList<>(requested.size());
        List<SearchResult> observations = new ArrayList<>();
        int supplied = 0;
        int filtered = 0;
        SystemSettingsDTO config;
        try {
            config = systemSettingService.getSearchSettings();
        } catch (Exception error) {
            String message = safeFailure(error);
            for (String providerId : requested) {
                ProviderFailure failure = new ProviderFailure(providerId, message);
                SearchBatch batch = SearchBatch.unavailable(providerId,
                        "搜索 provider 配置读取失败: " + message, List.of(failure));
                batches.add(batch);
                failures.add(failure);
            }
            return new SearchUnion(requested, batches, observations, supplied, filtered, failures);
        }
        for (String providerId : requested) {
            SearchBatch batch;
            try {
                batch = searchExplicitProvider(providerId, query, config);
            } catch (Exception error) {
                String message = safeFailure(error);
                batch = SearchBatch.unavailable(providerId,
                        "搜索 provider " + providerId + " 失败: " + message,
                        List.of(new ProviderFailure(providerId, message)));
            }
            if (batch == null) {
                batch = SearchBatch.unavailable(providerId,
                        "搜索 provider 返回空响应",
                        List.of(new ProviderFailure(providerId, "provider returned null response")));
            }
            batches.add(batch);
            supplied += batch.suppliedResultCount();
            filtered += batch.filteredResultCount();
            observations.addAll(withProviderProvenance(batch.results(), providerId));
            failures.addAll(batch.failures());
        }
        return new SearchUnion(requested, batches, observations, supplied, filtered, failures);
    }

    /** Parameter-order convenience overload for explicit provider unions. */
    public SearchUnion searchCandidates(SearchQuery searchQuery, List<String> providerIds) {
        return searchCandidates(providerIds, searchQuery);
    }

    private ProviderAttempt tryProvider(SearchProvider provider, SearchQuery searchQuery,
                                       SystemSettingsDTO config) {
        try {
            // 先查缓存
            String cacheKey = searchCache.buildKey(provider.id(), searchQuery);
            List<SearchResult> cached = searchCache.get(cacheKey);
            if (cached != null) {
                log.info("搜索缓存命中 (provider={}, query='{}')", provider.id(), searchQuery.query());
                return successful(provider.id(), true, cached, searchQuery);
            }

            // 缓存未命中，调用 provider
            List<SearchResult> results = provider.search(searchQuery, config);
            if (results == null || results.isEmpty()) {
                log.debug("Provider {} 返回空结果", provider.id());
                return failed(provider.id(), "provider returned no results");
            }

            // Cache the provider's raw response. Filtering is deliberately
            // applied after cache lookup and before exposing rows, so every
            // provider follows the same include/exclude-domain contract.
            searchCache.put(cacheKey, results);

            return successful(provider.id(), false, results, searchQuery);
        } catch (Exception e) {
            String message = safeFailure(e);
            log.warn("Provider {} 搜索失败: {}", provider.id(), message);
            return failed(provider.id(), message);
        }
    }

    private static ProviderAttempt successful(String providerId, boolean fromCache,
                                              List<SearchResult> supplied,
                                              SearchQuery query) {
        int suppliedCount = supplied == null ? 0 : supplied.size();
        // A malformed extension provider must not make an otherwise useful
        // response fail because List.copyOf rejects null elements. Count the
        // malformed rows as filtered so the caller can see the loss.
        List<SearchResult> raw = supplied == null ? List.of() : supplied.stream()
                .filter(java.util.Objects::nonNull)
                .map(result -> withProviderProvenance(result, providerId))
                .toList();
        List<SearchResult> filtered = filterByDomain(raw, query);
        SearchBatch batch = new SearchBatch(providerId, fromCache, filtered, "",
                suppliedCount, suppliedCount - filtered.size(), List.of());
        ProviderFailure failure = filtered.isEmpty() && suppliedCount > 0
                ? new ProviderFailure(providerId, "all provider results were removed by domain policy")
                : null;
        return new ProviderAttempt(batch, failure);
    }

    private static List<SearchResult> withProviderProvenance(List<SearchResult> results,
                                                              String providerId) {
        if (results == null || results.isEmpty()) return List.of();
        String effectiveProvider = providerId == null ? "" : providerId;
        List<SearchResult> normalized = new ArrayList<>(results.size());
        for (SearchResult result : results) {
            if (result == null) continue;
            // Copy instead of mutating provider-owned objects. The explicit
            // call boundary is authoritative even when an extension forgot
            // to populate providerId (or supplied a stale value).
            normalized.add(SearchResult.builder()
                    .title(result.getTitle())
                    .url(result.getUrl())
                    .snippet(result.getSnippet())
                    .source(result.getSource())
                    .date(result.getDate())
                    .providerId(effectiveProvider)
                    .relevanceScore(result.getRelevanceScore())
                    .build());
        }
        return List.copyOf(normalized);
    }

    private static SearchResult withProviderProvenance(SearchResult result,
                                                        String providerId) {
        if (result == null) return null;
        String effectiveProvider = providerId == null ? "" : providerId;
        return SearchResult.builder()
                .title(result.getTitle())
                .url(result.getUrl())
                .snippet(result.getSnippet())
                .source(result.getSource())
                .date(result.getDate())
                .providerId(effectiveProvider)
                .relevanceScore(result.getRelevanceScore())
                .build();
    }

    private static List<String> normalizeProviderIds(List<String> providerIds) {
        if (providerIds == null || providerIds.isEmpty()) return List.of();
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String raw : providerIds) {
            if (raw == null || raw.isBlank()) continue;
            for (String token : raw.split("[,\\s]+")) {
                String id = token.trim();
                if (!id.isBlank() && normalized.stream()
                        .noneMatch(existing -> existing.equalsIgnoreCase(id))) {
                    normalized.add(id);
                }
            }
        }
        return List.copyOf(normalized);
    }

    private static ProviderAttempt failed(String providerId, String message) {
        return new ProviderAttempt(null,
                new ProviderFailure(providerId, message == null || message.isBlank()
                        ? "provider search failed" : message));
    }

    /**
     * Return whole, structured candidate rows under a deterministic context
     * budget. Search snippets remain explicitly non-evidence and are truncated
     * per row; URLs are kept intact up to the platform URL bound.
     */
    static String formatResults(List<SearchResult> results, String providerId, boolean fromCache) {
        return formatResults(new SearchBatch(providerId, fromCache, results, ""));
    }

    private static String formatResults(SearchBatch batch) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        List<SearchResult> results = batch == null ? List.of() : batch.results();
        int supplied = batch == null ? 0 : batch.suppliedResultCount();
        for (int i = 0; i < results.size(); i++) {
            SearchResult item = results.get(i);
            if (item == null || item.getUrl() == null || item.getUrl().isBlank()) continue;
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("rank", i + 1);
            candidate.put("title", bounded(item.getTitle(), 240));
            candidate.put("url", bounded(item.getUrl(), 2048));
            candidate.put("source", bounded(item.getSource(), 160));
            candidate.put("publishedAtHint", bounded(item.getDate(), 128));
            candidate.put("providerId", bounded(item.getProviderId(), 64));
            candidate.put("relevanceScore", item.getRelevanceScore());
            String snippet = item.getSnippet() == null ? "" : item.getSnippet();
            candidate.put("snippet", bounded(snippet, MAX_SNIPPET_CHARS));
            candidate.put("snippetTruncated", snippet.length() > MAX_SNIPPET_CHARS);
            candidates.add(candidate);
            if (serializeEnvelope(batch, candidates).length() > MAX_FORMATTED_CHARS) {
                candidates.removeLast();
                break;
            }
        }
        return serializeEnvelope(batch, candidates);
    }

    private static String serializeEnvelope(SearchBatch batch,
                                            List<Map<String, Object>> candidates) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("mode", "untrusted_search_candidates");
        envelope.put("providerId", batch == null ? "" : batch.providerId());
        envelope.put("cached", batch != null && batch.fromCache());
        envelope.put("evidenceEligible", false);
        envelope.put("message", batch == null || batch.message().isBlank()
                ? "Search snippets are discovery hints only. Capture and read the source URL before quoting or inserting evidence."
                : batch.message());
        int supplied = batch == null ? 0 : batch.suppliedResultCount();
        envelope.put("suppliedResultCount", supplied);
        envelope.put("returnedResultCount", candidates.size());
        envelope.put("filteredResultCount", batch == null ? 0 : batch.filteredResultCount());
        int filtered = batch == null ? 0 : batch.filteredResultCount();
        envelope.put("omittedDueToBudget", Math.max(0, supplied - filtered - candidates.size()));
        envelope.put("providerFailures", batch == null ? List.of() : batch.failures());
        envelope.put("results", candidates);
        try {
            return JSON.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize structured search candidates", e);
        }
    }

    /** Apply the same host-boundary matching contract to every provider response. */
    static List<SearchResult> filterByDomain(List<SearchResult> results, SearchQuery query) {
        if (results == null || results.isEmpty()) return List.of();
        if (query == null || (!query.hasIncludeDomains() && !query.hasExcludeDomains())) {
            return List.copyOf(results);
        }
        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult result : results) {
            if (result == null || result.getUrl() == null || result.getUrl().isBlank()) continue;
            String host = host(result.getUrl());
            if (host.isBlank()) continue;
            boolean included = !query.hasIncludeDomains()
                    || query.includeDomains().stream().anyMatch(domain -> domainMatches(host, domain));
            boolean excluded = query.hasExcludeDomains()
                    && query.excludeDomains().stream().anyMatch(domain -> domainMatches(host, domain));
            if (included && !excluded) filtered.add(result);
        }
        return List.copyOf(filtered);
    }

    private static boolean domainMatches(String host, String domain) {
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("www.")) normalizedHost = normalizedHost.substring(4);
        String normalizedDomain = normalizeDomain(domain);
        return !normalizedHost.isBlank() && !normalizedDomain.isBlank()
                && (normalizedHost.equals(normalizedDomain)
                || normalizedHost.endsWith("." + normalizedDomain));
    }

    private static String host(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return "";
        try {
            URI uri = URI.create(rawUrl.trim());
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeDomain(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "");
        if (value.startsWith("*.")) value = value.substring(2);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(0, colon);
        return value.trim();
    }

    private static String safeFailure(Throwable error) {
        if (error == null) return "provider search failed";
        String value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        // Provider exceptions occasionally echo request headers/URLs. Keep
        // diagnostics useful while preventing accidental credential leakage.
        value = value.replaceAll("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+", "$1[redacted]")
                .replaceAll("(?i)((?:api[_-]?key|token|secret)\\s*[=:]\\s*)[^\\s,;]+", "$1[redacted]")
                .replaceAll("[\\r\\n]+", " ").trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= max) return normalized;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(normalized.charAt(end - 1))) end--;
        return normalized.substring(0, end);
    }

    public record SearchBatch(String providerId,
                              boolean fromCache,
                              List<SearchResult> results,
                              String message,
                              int suppliedResultCount,
                              int filteredResultCount,
                              List<ProviderFailure> failures) {
        /** Four-field constructor retained for existing callers/tests. */
        public SearchBatch(String providerId,
                           boolean fromCache,
                           List<SearchResult> results,
                           String message) {
            this(providerId, fromCache, results, message,
                    results == null ? 0 : results.size(), 0, List.of());
        }

        public SearchBatch {
            providerId = providerId == null ? "" : providerId;
            results = results == null ? List.of() : List.copyOf(results);
            message = message == null ? "" : message;
            suppliedResultCount = Math.max(0, suppliedResultCount);
            filteredResultCount = Math.max(0, filteredResultCount);
            failures = failures == null ? List.of() : failures.stream()
                    .filter(java.util.Objects::nonNull).toList();
        }

        public static SearchBatch unavailable(String message) {
            return unavailable("", message, List.of());
        }

        public static SearchBatch unavailable(String providerId, String message,
                                              List<ProviderFailure> failures) {
            return new SearchBatch(providerId, false, List.of(), message, 0, 0, failures);
        }

        public static SearchBatch unavailable(String message, List<ProviderFailure> failures) {
            return unavailable("", message, failures);
        }

        SearchBatch withFailures(List<ProviderFailure> additional) {
            if (additional == null || additional.isEmpty()) return this;
            List<ProviderFailure> merged = new ArrayList<>(failures);
            merged.addAll(additional);
            return new SearchBatch(providerId, fromCache, results, message,
                    suppliedResultCount, filteredResultCount, merged);
        }

        /** Alias names make the raw-vs-filtered distinction explicit to callers. */
        public int rawResultCount() {
            return suppliedResultCount;
        }

        public int filteredOutCount() {
            return filteredResultCount;
        }
    }

    public record ProviderFailure(String providerId, String message) {
        public ProviderFailure {
            providerId = providerId == null ? "" : providerId;
            message = message == null || message.isBlank() ? "provider search failed" : message;
        }
    }

    /**
     * A loss-aware result of an explicit multi-provider search. The flattened
     * {@link #results()} list is an observation union and may contain the same
     * URL more than once; callers that know their editorial URL/story policy
     * should de-duplicate it while retaining the per-provider {@link #batches()}
     * for audit and marginal-recall accounting.
     */
    public record SearchUnion(List<String> requestedProviderIds,
                              List<SearchBatch> batches,
                              List<SearchResult> results,
                              int suppliedResultCount,
                              int filteredResultCount,
                              List<ProviderFailure> failures) {
        public SearchUnion {
            requestedProviderIds = requestedProviderIds == null
                    ? List.of() : List.copyOf(requestedProviderIds);
            batches = batches == null ? List.of() : List.copyOf(batches);
            results = results == null ? List.of() : List.copyOf(results);
            suppliedResultCount = Math.max(0, suppliedResultCount);
            filteredResultCount = Math.max(0, filteredResultCount);
            failures = failures == null ? List.of() : failures.stream()
                    .filter(java.util.Objects::nonNull).toList();
        }

        public int returnedResultCount() {
            return results.size();
        }

        public int attemptedProviderCount() {
            return batches.size();
        }

        public int successfulProviderCount() {
            return (int) batches.stream()
                    .filter(batch -> batch != null && batch.failures().isEmpty()
                            && (batch.suppliedResultCount() > 0 || !batch.results().isEmpty()))
                    .count();
        }
    }

    private record ProviderAttempt(SearchBatch batch, ProviderFailure failure) {
    }
}

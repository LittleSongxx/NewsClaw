package vip.newsclaw.plugin.bridge;

import lombok.extern.slf4j.Slf4j;
import vip.newsclaw.plugin.api.search.PluginSearchProvider;
import vip.newsclaw.plugin.api.search.PluginSearchQuery;
import vip.newsclaw.plugin.api.search.PluginSearchResult;
import vip.newsclaw.system.model.SystemSettingsDTO;
import vip.newsclaw.tool.search.SearchProvider;
import vip.newsclaw.tool.search.SearchQuery;
import vip.newsclaw.tool.search.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge that wraps a plugin's {@link PluginSearchProvider} into the platform's
 * internal {@link SearchProvider} interface.
 * <p>
 * The platform-side {@link SystemSettingsDTO} is intentionally ignored — plugin
 * providers read their own config via {@code PluginContext#getConfig}, keeping
 * the SDK free of server types.
 * <p>
 * Fault isolation: {@code search()} may throw (the caller's provider-fallback
 * chain handles that), but everything consulted on unguarded paths is insulated
 * from plugin code here — metadata ({@code id/label/requiresCredential/autoDetectOrder})
 * is snapshotted once at registration time (where a throw is caught and rolled
 * back by the plugin loader), because it is later read inside {@code allSorted()}'s
 * sort comparator and the settings catalog with no per-provider guard; and
 * {@code isAvailable()} degrades to {@code false} on any plugin exception, because
 * it runs inside {@code resolve()} on every web_search call — a throwing
 * availability check must not take every other provider down with it.
 *
 * @author NewsClaw Team
 */
@Slf4j
public class PluginSearchBridge implements SearchProvider {

    private final PluginSearchProvider delegate;
    private final String id;
    private final String label;
    private final boolean requiresCredential;
    private final int autoDetectOrder;

    public PluginSearchBridge(PluginSearchProvider delegate) {
        this.delegate = delegate;
        this.id = delegate.id();
        this.label = delegate.label();
        this.requiresCredential = delegate.requiresCredential();
        this.autoDetectOrder = delegate.autoDetectOrder();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean requiresCredential() {
        return requiresCredential;
    }

    @Override
    public int autoDetectOrder() {
        return autoDetectOrder;
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            return delegate.isAvailable();
        } catch (Exception e) {
            log.warn("插件搜索提供商 {} 的 isAvailable() 抛出异常，按不可用处理: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public List<SearchResult> search(String query, SystemSettingsDTO config) {
        return search(SearchQuery.of(query), config);
    }

    @Override
    public List<SearchResult> search(SearchQuery searchQuery, SystemSettingsDTO config) {
        PluginSearchQuery pluginQuery = new PluginSearchQuery(
                searchQuery.query(),
                searchQuery.freshness(),
                searchQuery.language(),
                // Plugin API v1 promises 1-10. Core providers may request up
                // to 20 for bounded vertical retrieval, but that must not
                // silently widen the stable plugin contract.
                Math.min(10, searchQuery.resolvedCount())
        );
        List<PluginSearchResult> pluginResults = delegate.search(pluginQuery);
        if (pluginResults == null) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>(pluginResults.size());
        for (PluginSearchResult r : pluginResults) {
            results.add(SearchResult.builder()
                    .title(r.title())
                    .url(r.url())
                    .snippet(r.snippet())
                    .source(r.source())
                    .date(r.date())
                    .providerId(id)
                    .build());
        }
        return results;
    }
}

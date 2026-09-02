package vip.newsclaw.news.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Calibratable admission bounds for AI-news discovery.
 *
 * <p>The percentages are quotas rather than ranking boosts. Candidates with a
 * parseable publication hint outside the requested half-open window are never
 * admitted; undated rows may only enter through the bounded exploration
 * lanes below.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "newsclaw.ai-news.discovery")
public class AiNewsDiscoveryProperties {

    /**
     * Explicit, ordered web-search providers for the news vertical. An empty
     * list preserves the legacy auto-detect/fallback behaviour. When set,
     * every discovery lane is sent to each provider and the resulting
     * observations are fused downstream. The list is intentionally kept in
     * configuration rather than inferred from credentials so a run can be
     * replayed with the exact provider policy that produced it.
     */
    private List<String> providerIds = new ArrayList<>();

    /** Prevent one publisher/search-index host from monopolising the capture queue. */
    private int maxCandidatesPerHost = 4;
    /** Keep one representative plus at most one independent corroborating publisher. */
    private int maxCandidatesPerStory = 2;
    /** Current but unregistered open-Web rows are useful, but remain a minority. */
    private int currentOpenWebPercent = 20;
    /**
     * Aggregate ceiling across every undated lane. Unknown rows remain in the
     * frozen observation ledger, but do not enter the automatic capture queue
     * unless an operator explicitly opts into exploration.
     */
    private int maxUnknownPercent = 0;
    /** Undated official pages retained for source-capture exploration. */
    private int unknownOfficialPercent = 20;
    /** Undated registered-media pages retained for source-capture exploration. */
    private int unknownMediaPercent = 20;
    /** Undated unregistered pages are disabled by default; operators may opt into exploration. */
    private int unknownOpenWebPercent = 0;

    /**
     * Return a stable provider list (case-insensitive de-duplication). Spring's relaxed binder
     * may provide a comma-separated environment value as one list element, so
     * accept commas/whitespace here as well. Blank and duplicate ids are
     * ignored while preserving operator order.
     */
    public List<String> normalizedProviderIds() {
        if (providerIds == null || providerIds.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
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
}

package vip.newsclaw.news.source;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Versioned, fail-closed transport catalog for AI-news source endpoints. */
@Component
public class AiNewsSourceCatalog {

    static final String DEFAULT_RESOURCE =
            "skills/ai_news_radar/references/source_catalog.yml";

    private final int version;
    private final List<Endpoint> endpoints;
    private final Set<String> enabledIds;

    @Autowired
    public AiNewsSourceCatalog(
            AiNewsSourceRegistry sourceRegistry,
            @Value("${newsclaw.ai-news.sources.catalog.enabled-endpoint-ids:}")
            String enabledEndpointIds) {
        this(sourceRegistry, loadDefaultDocument(), enabledEndpointIds);
    }

    AiNewsSourceCatalog(AiNewsSourceRegistry sourceRegistry,
                        Map<String, Object> document,
                        String enabledEndpointIds) {
        this.version = integer(document.get("version"), "catalog version");
        if (version <= 0) throw new IllegalStateException("source catalog version must be positive");
        this.endpoints = List.copyOf(parseEndpoints(sourceRegistry, document.get("endpoints")));
        Set<String> requested = csvSet(enabledEndpointIds);
        Set<String> known = endpoints.stream().map(Endpoint::endpointId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> unknown = new LinkedHashSet<>(requested);
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("unknown AI news source endpoint id(s): " + unknown);
        }
        LinkedHashSet<String> effective = endpoints.stream()
                .filter(Endpoint::enabledByDefault).map(Endpoint::endpointId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        effective.addAll(requested);
        this.enabledIds = Set.copyOf(effective);
    }

    public int version() {
        return version;
    }

    public List<Endpoint> all() {
        return endpoints;
    }

    public List<Endpoint> enabled(EndpointAdapter adapter) {
        return endpoints.stream().filter(endpoint -> endpoint.adapter() == adapter)
                .filter(endpoint -> enabledIds.contains(endpoint.endpointId())).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Endpoint> parseEndpoints(AiNewsSourceRegistry sourceRegistry, Object raw) {
        if (!(raw instanceof List<?> rows)) {
            throw new IllegalStateException("source catalog endpoints must be a list");
        }
        List<Endpoint> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> adapterUrls = new LinkedHashSet<>();
        for (Object rawRow : rows) {
            if (!(rawRow instanceof Map<?, ?> row)) {
                throw new IllegalStateException("source catalog endpoint must be a map");
            }
            String id = required(row.get("endpoint_id"), "endpoint_id");
            if (!ids.add(id)) throw new IllegalStateException("duplicate source endpoint id: " + id);
            String sourceKey = required(row.get("source_key"), id + ".source_key");
            EndpointAdapter adapter = EndpointAdapter.parse(required(
                    row.get("adapter"), id + ".adapter"));
            URI url = httpUri(required(row.get("url"), id + ".url"), id);
            String adapterUrl = adapter + "|" + url.normalize();
            if (!adapterUrls.add(adapterUrl)) {
                throw new IllegalStateException("duplicate source endpoint URL for " + adapter + ": " + url);
            }
            String classifiedKey = sourceRegistry.publisherSourceKey(url.toString())
                    .orElseThrow(() -> new IllegalStateException("source endpoint is outside the reviewed "
                            + "registry: " + id));
            if (!sourceKey.equals(classifiedKey)) {
                throw new IllegalStateException("source endpoint '" + id + "' declares source_key '"
                        + sourceKey + "' but registry classifies it as '" + classifiedKey + "'");
            }
            Map<?, ?> rights = row.get("rights") instanceof Map<?, ?> value ? value : Map.of();
            String rightsStatus = required(rights.get("status"), id + ".rights.status");
            String rawRetention = required(rights.get("raw_retention"),
                    id + ".rights.raw_retention");
            String termsUrl = string(rights.get("usage_terms_url"));
            String robotsStatus = required(row.get("robots_status"), id + ".robots_status");
            boolean evidenceEligible = bool(row.get("evidence_eligible"));
            if (evidenceEligible && !AiNewsSourceGovernancePolicy.evidenceEligible(
                    true, rightsStatus, robotsStatus)) {
                throw new IllegalStateException(id + " is evidence_eligible but rights/robots status "
                        + "is not in the reviewed allowlist");
            }
            int pollSeconds = integer(row.get("poll_interval_seconds"),
                    id + ".poll_interval_seconds");
            if (pollSeconds < 60 || pollSeconds > 86_400) {
                throw new IllegalStateException(id + ".poll_interval_seconds must be within [60,86400]");
            }
            out.add(new Endpoint(id, sourceKey, adapter, url,
                    bool(row.get("enabled_by_default")), stringList(row.get("languages")),
                    stringList(row.get("categories")), pollSeconds,
                    evidenceEligible, rightsStatus, rawRetention,
                    termsUrl, robotsStatus,
                    string(row.get("last_verified_at"))));
        }
        return out;
    }

    private static URI httpUri(String value, String id) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException();
            return uri;
        } catch (Exception e) {
            throw new IllegalStateException(id + ".url must be an absolute HTTP(S) URI", e);
        }
    }

    private static Set<String> csvSet(String value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (value == null) return out;
        for (String item : value.split("[,;\\s]+")) {
            if (!item.isBlank()) out.add(item.trim());
        }
        return out;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(AiNewsSourceCatalog::string)
                .filter(item -> !item.isBlank()).distinct().toList();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool ? bool
                : "true".equalsIgnoreCase(string(value));
    }

    private static int integer(Object value, String field) {
        try {
            return value instanceof Number number ? number.intValue()
                    : Integer.parseInt(string(value));
        } catch (Exception e) {
            throw new IllegalStateException(field + " must be an integer", e);
        }
    }

    private static String required(Object value, String field) {
        String text = string(value);
        if (text.isBlank()) throw new IllegalStateException(field + " is required");
        return text;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadDefaultDocument() {
        try (InputStream input = new ClassPathResource(DEFAULT_RESOURCE).getInputStream()) {
            Object document = new Yaml().load(input);
            if (document instanceof Map<?, ?> map) return (Map<String, Object>) map;
            throw new IllegalStateException("AI news source catalog root must be a YAML map");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load AI news source catalog", e);
        }
    }

    public enum EndpointAdapter {
        FEED,
        NEWS_SITEMAP,
        WEBSUB,
        OFFICIAL_API,
        GITHUB_RELEASES,
        ARXIV;

        static EndpointAdapter parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (Exception e) {
                throw new IllegalStateException("unsupported source endpoint adapter: " + value, e);
            }
        }
    }

    public record Endpoint(String endpointId,
                           String sourceKey,
                           EndpointAdapter adapter,
                           URI url,
                           boolean enabledByDefault,
                           List<String> languages,
                           List<String> categories,
                           int pollIntervalSeconds,
                           boolean evidenceEligible,
                           String rightsStatus,
                           String rawRetention,
                           String usageTermsUrl,
                           String robotsStatus,
                           String lastVerifiedAt) {
        public Endpoint {
            languages = List.copyOf(languages);
            categories = List.copyOf(categories);
        }

        void addProvenance(Map<String, Object> metadata, int catalogVersion) {
            metadata.put("sourceCatalogVersion", catalogVersion);
            metadata.put("sourceEndpointId", endpointId);
            // Endpoint ownership is not the same as the linked article's
            // publisher identity; adapters classify the article URL again.
            metadata.put("sourceEndpointOwnerKey", sourceKey);
            metadata.put("sourceEndpointRightsStatus", rightsStatus);
            metadata.put("sourceEndpointRawRetention", rawRetention);
            metadata.put("sourceEndpointRobotsStatus", robotsStatus);
            metadata.put("sourceEndpointEvidenceEligible", evidenceEligible);
        }
    }
}

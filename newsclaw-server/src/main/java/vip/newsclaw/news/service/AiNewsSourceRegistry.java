package vip.newsclaw.news.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fail-closed runtime view of the AI news source registry used by both the
 * radar skill and backend verification policy.
 */
@Component
public class AiNewsSourceRegistry {

    static final String DEFAULT_RESOURCE =
            "skills/ai_news_radar/references/source_registry.yml";

    private final Map<String, String> officialDomains;
    private final Map<String, String> officialUrlPrefixes;
    private final Map<String, String> mediaDomains;
    private final Map<String, String> publisherIdentityDomains;
    private final Map<String, List<String>> mediaDomainsByGroup;
    private final List<OfficialSource> officialSources;

    public AiNewsSourceRegistry() {
        this(loadDefaultDocument());
    }

    AiNewsSourceRegistry(Map<String, Object> document) {
        Map<String, String> officialDomainIndex = new LinkedHashMap<>();
        Map<String, String> officialPrefixIndex = new LinkedHashMap<>();
        Map<String, String> mediaDomainIndex = new LinkedHashMap<>();
        Map<String, String> publisherIdentityIndex = new LinkedHashMap<>();
        indexGroups(document.get("official"), officialDomainIndex, officialPrefixIndex);
        indexGroups(document.get("media"), mediaDomainIndex, null);
        officialDomainIndex.forEach((domain, sourceKey) -> putUnique(
                publisherIdentityIndex, domain, sourceKey, "publisher identity domain"));
        mediaDomainIndex.forEach((domain, sourceKey) -> putUnique(
                publisherIdentityIndex, domain, sourceKey, "publisher identity domain"));
        // Ownership-only entries let a governed publisher feed attest metadata
        // without silently promoting an open-web site to official/trusted media.
        indexGroups(document.get("publisher_identity"), publisherIdentityIndex, null);
        if (officialDomainIndex.isEmpty() || mediaDomainIndex.isEmpty()) {
            throw new IllegalStateException("AI news source registry must define official and media domains");
        }
        this.officialDomains = Map.copyOf(officialDomainIndex);
        this.officialUrlPrefixes = Map.copyOf(officialPrefixIndex);
        this.mediaDomains = Map.copyOf(mediaDomainIndex);
        this.publisherIdentityDomains = Map.copyOf(publisherIdentityIndex);
        this.mediaDomainsByGroup = Map.copyOf(parseMediaDomainsByGroup(document.get("media")));
        this.officialSources = List.copyOf(parseOfficialSources(document.get("official")));
    }

    public boolean isOfficialUrl(String url) {
        return officialSourceKey(url).isPresent();
    }

    public boolean isTrustedMediaUrl(String url) {
        return trustedMediaSourceKey(url).isPresent();
    }

    public Optional<String> officialSourceKey(String url) {
        String normalizedUrl = normalizedUrl(url);
        if (normalizedUrl.isBlank()) return Optional.empty();
        for (Map.Entry<String, String> entry : officialUrlPrefixes.entrySet()) {
            if (normalizedUrl.startsWith(entry.getKey())) return Optional.of(entry.getValue());
        }
        return sourceKeyForDomain(host(url), officialDomains);
    }

    /**
     * The returned key is the editorially independent publisher identity, not
     * merely a hostname. Two domains owned by one publisher therefore count as
     * one corroborating source.
     */
    public Optional<String> trustedMediaSourceKey(String url) {
        return sourceKeyForDomain(host(url), mediaDomains);
    }

    /**
     * Publisher ownership identity used for exact structured-source binding.
     * A hit here does not imply official or trusted-media status; callers that
     * rank or verify evidence must continue to use the tier-specific methods.
     */
    public Optional<String> publisherSourceKey(String url) {
        return officialSourceKey(url)
                .or(() -> trustedMediaSourceKey(url))
                .or(() -> sourceKeyForDomain(host(url), publisherIdentityDomains));
    }

    /** Stable, registry-owned official discovery plan; model text cannot add a trusted domain. */
    public List<OfficialSource> officialSearchPlan(String category) {
        String requested = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        if (requested.isBlank() || "all".equals(requested)) return officialSources;
        java.util.Set<String> groups = switch (requested) {
            case "model", "open_source", "security" -> Set.of("models_and_research");
            case "product" -> Set.of("global_products", "china_products");
            case "robotics" -> Set.of("robotics");
            case "infrastructure" -> Set.of("infrastructure", "global_products");
            default -> Set.of();
        };
        if (groups.isEmpty()) return officialSources;
        List<OfficialSource> selected = officialSources.stream()
                .filter(source -> groups.contains(source.group())).toList();
        return selected.isEmpty() ? officialSources : selected;
    }

    public List<String> officialSearchDomains() {
        return officialSources.stream().flatMap(source -> source.domains().stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    public List<String> trustedMediaSearchDomains() {
        return List.copyOf(mediaDomains.keySet());
    }

    /** Language-aligned media lane, owned by the reviewed registry. */
    public List<String> trustedMediaSearchDomains(String group) {
        if (group == null || group.isBlank()) return trustedMediaSearchDomains();
        return mediaDomainsByGroup.getOrDefault(group.trim().toLowerCase(Locale.ROOT), List.of());
    }

    private static Optional<String> sourceKeyForDomain(String host, Map<String, String> index) {
        if (host.isBlank()) return Optional.empty();
        return index.entrySet().stream()
                .filter(entry -> host.equals(entry.getKey()) || host.endsWith("." + entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private static void indexGroups(Object rawGroups, Map<String, String> domainIndex,
                                    Map<String, String> prefixIndex) {
        if (!(rawGroups instanceof Map<?, ?> groups)) return;
        for (Object groupValue : groups.values()) {
            if (!(groupValue instanceof List<?> entries)) continue;
            for (Object rawEntry : entries) {
                if (!(rawEntry instanceof Map<?, ?> entry)) continue;
                String sourceKey = string(entry.get("source_key"));
                if (sourceKey.isBlank()) {
                    throw new IllegalStateException("source_registry.yml entry is missing source_key");
                }
                boolean prefixOnly = "prefix_only".equalsIgnoreCase(string(entry.get("domain_match")));
                Object rawDomains = entry.get("domains");
                // Some hosts (for example model hubs) contain third-party content under
                // the same registrable domain. Keep those domains in the discovery plan,
                // but never promote the whole host to first-party evidence.
                if (!prefixOnly && rawDomains instanceof List<?> domains) {
                    for (Object rawDomain : domains) {
                        String domain = normalizeDomain(string(rawDomain));
                        if (!domain.isBlank()) putUnique(domainIndex, domain, sourceKey, "domain");
                    }
                }
                if (prefixIndex != null && entry.get("url_prefixes") instanceof List<?> prefixes) {
                    for (Object rawPrefix : prefixes) {
                        String prefix = normalizedUrl(string(rawPrefix));
                        if (!prefix.isBlank()) putUnique(prefixIndex, prefix, sourceKey, "URL prefix");
                    }
                }
                if (prefixOnly && prefixIndex != null
                        && (!(entry.get("url_prefixes") instanceof List<?> prefixes) || prefixes.isEmpty())) {
                    throw new IllegalStateException("prefix_only source '" + sourceKey
                            + "' must define at least one url_prefixes entry");
                }
            }
        }
    }

    private static List<OfficialSource> parseOfficialSources(Object rawGroups) {
        List<OfficialSource> out = new ArrayList<>();
        if (!(rawGroups instanceof Map<?, ?> groups)) return out;
        for (Map.Entry<?, ?> groupEntry : groups.entrySet()) {
            String group = string(groupEntry.getKey()).toLowerCase(Locale.ROOT);
            if (!(groupEntry.getValue() instanceof List<?> entries)) continue;
            for (Object rawEntry : entries) {
                if (!(rawEntry instanceof Map<?, ?> entry)) continue;
                String sourceKey = string(entry.get("source_key"));
                if (sourceKey.isBlank()) continue;
                Object rawQueryDomains = entry.containsKey("query_domains")
                        ? entry.get("query_domains") : entry.get("domains");
                List<String> domains = stringList(rawQueryDomains).stream()
                        .map(AiNewsSourceRegistry::normalizeSearchDomain)
                        .filter(value -> !value.isBlank()).toList();
                List<String> querySites = stringList(entry.get("query_sites"));
                out.add(new OfficialSource(group, sourceKey, string(entry.get("name")),
                        domains, querySites));
            }
        }
        return out;
    }

    private static Map<String, List<String>> parseMediaDomainsByGroup(Object rawGroups) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!(rawGroups instanceof Map<?, ?> groups)) return out;
        for (Map.Entry<?, ?> groupEntry : groups.entrySet()) {
            LinkedHashSet<String> domains = new LinkedHashSet<>();
            if (groupEntry.getValue() instanceof List<?> entries) {
                for (Object rawEntry : entries) {
                    if (!(rawEntry instanceof Map<?, ?> entry)) continue;
                    stringList(entry.get("domains")).stream()
                            .map(AiNewsSourceRegistry::normalizeDomain)
                            .filter(value -> !value.isBlank()).forEach(domains::add);
                }
            }
            out.put(string(groupEntry.getKey()).toLowerCase(Locale.ROOT), List.copyOf(domains));
        }
        return out;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(AiNewsSourceRegistry::string)
                .filter(item -> !item.isBlank()).toList();
    }

    private static void putUnique(Map<String, String> index, String key, String sourceKey, String kind) {
        String existing = index.putIfAbsent(key, sourceKey);
        if (existing != null && !existing.equals(sourceKey)) {
            throw new IllegalStateException("AI news " + kind + " '" + key
                    + "' belongs to both '" + existing + "' and '" + sourceKey + "'");
        }
    }

    private static String normalizeDomain(String value) {
        String domain = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (domain.startsWith("http://") || domain.startsWith("https://")) return host(domain);
        int slash = domain.indexOf('/');
        if (slash >= 0) domain = domain.substring(0, slash);
        return domain.startsWith("www.") ? domain.substring(4) : domain;
    }

    /** Search providers may distinguish a reviewed subdomain from its parent. */
    private static String normalizeSearchDomain(String value) {
        String domain = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (domain.startsWith("http://") || domain.startsWith("https://")) {
            try {
                String host = new URI(domain).getHost();
                return host == null ? "" : host.toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {
                return "";
            }
        }
        int slash = domain.indexOf('/');
        return slash >= 0 ? domain.substring(0, slash) : domain;
    }

    static String host(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String value = url.contains("://") ? url.trim() : "https://" + url.trim();
            String host = new URI(value).getHost();
            if (host == null) return "";
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizedUrl(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            URI uri = new URI(url.trim());
            if (uri.getHost() == null) return "";
            String path = uri.getPath() == null ? "" : uri.getPath();
            return (uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                    + uri.getHost().toLowerCase(Locale.ROOT) + path).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadDefaultDocument() {
        try (InputStream input = new ClassPathResource(DEFAULT_RESOURCE).getInputStream()) {
            Object document = new Yaml().load(input);
            if (document instanceof Map<?, ?> map) return (Map<String, Object>) map;
            throw new IllegalStateException("AI news source registry root must be a YAML map");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load AI news source registry", e);
        }
    }

    public record OfficialSource(String group,
                                 String sourceKey,
                                 String name,
                                 List<String> domains,
                                 List<String> querySites) {
        public OfficialSource {
            domains = domains == null ? List.of() : List.copyOf(domains);
            querySites = querySites == null ? List.of() : List.copyOf(querySites);
        }
    }
}

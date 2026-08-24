package vip.newsclaw.news.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

    public AiNewsSourceRegistry() {
        this(loadDefaultDocument());
    }

    AiNewsSourceRegistry(Map<String, Object> document) {
        Map<String, String> officialDomainIndex = new LinkedHashMap<>();
        Map<String, String> officialPrefixIndex = new LinkedHashMap<>();
        Map<String, String> mediaDomainIndex = new LinkedHashMap<>();
        indexGroups(document.get("official"), officialDomainIndex, officialPrefixIndex);
        indexGroups(document.get("media"), mediaDomainIndex, null);
        if (officialDomainIndex.isEmpty() || mediaDomainIndex.isEmpty()) {
            throw new IllegalStateException("AI news source registry must define official and media domains");
        }
        this.officialDomains = Map.copyOf(officialDomainIndex);
        this.officialUrlPrefixes = Map.copyOf(officialPrefixIndex);
        this.mediaDomains = Map.copyOf(mediaDomainIndex);
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
                Object rawDomains = entry.get("domains");
                if (rawDomains instanceof List<?> domains) {
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
            }
        }
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
}

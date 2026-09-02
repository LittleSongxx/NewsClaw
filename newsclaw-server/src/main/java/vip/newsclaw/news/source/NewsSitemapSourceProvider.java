package vip.newsclaw.news.source;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google News Sitemap adapter with bounded one-level sitemap-index traversal.
 * Generic sitemap URLs without news publication metadata are deliberately not
 * promoted to news candidates. Callers receive discovery hints; only the
 * separately governed capture-time attestation service may promote a persisted
 * exact timestamp after URL, publisher and raw-transport checks.
 */
@Component
@Slf4j
public class NewsSitemapSourceProvider implements ScheduledNewsSourceProvider {

    static final int MAX_CHILD_SITEMAPS = 20;
    static final int MAX_ITEMS_PER_ROOT = 500;

    private final AiNewsSourceRegistry sourceRegistry;
    private final ConditionalNewsSourceHttpClient conditionalClient;
    private final HttpClient client;
    private final String sitemapList;
    private final Map<URI, AiNewsSourceCatalog.Endpoint> catalogEndpoints;
    private final int sourceCatalogVersion;
    private final List<NewsSourceEndpointDescriptor> endpointDescriptors;
    private final Map<URI, ParsedSitemap> cache = new ConcurrentHashMap<>();
    private volatile Instant lastCheckedAt;
    private volatile int lastSuccessfulDocuments;
    private volatile int lastFailedDocuments;
    private volatile int lastNotModifiedDocuments;
    private volatile long lastLatencyMs;

    @Autowired
    public NewsSitemapSourceProvider(
            AiNewsSourceRegistry sourceRegistry,
            AiNewsSourceCatalog sourceCatalog,
            @Value("${newsclaw.ai-news.sources.news-sitemaps.urls:}") String sitemapList) {
        this(sourceRegistry, mergeConfigured(sitemapList,
                        sourceCatalog.enabled(AiNewsSourceCatalog.EndpointAdapter.NEWS_SITEMAP)),
                HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(10)).build(),
                endpointIndex(sourceCatalog.enabled(AiNewsSourceCatalog.EndpointAdapter.NEWS_SITEMAP)),
                sourceCatalog.version());
    }

    NewsSitemapSourceProvider(AiNewsSourceRegistry sourceRegistry, String sitemapList,
                              HttpClient client) {
        this(sourceRegistry, sitemapList, client, Map.of(), 0);
    }

    private NewsSitemapSourceProvider(AiNewsSourceRegistry sourceRegistry, String sitemapList,
                                      HttpClient client,
                                      Map<URI, AiNewsSourceCatalog.Endpoint> catalogEndpoints,
                                      int sourceCatalogVersion) {
        this.sourceRegistry = sourceRegistry;
        this.sitemapList = sitemapList == null ? "" : sitemapList;
        this.client = client;
        this.conditionalClient = new ConditionalNewsSourceHttpClient(client);
        this.catalogEndpoints = Map.copyOf(catalogEndpoints);
        this.sourceCatalogVersion = sourceCatalogVersion;
        this.endpointDescriptors = endpointDescriptors(sourceRegistry, this.sitemapList,
                this.catalogEndpoints, sourceCatalogVersion);
    }

    @Override
    public String providerId() {
        return "news-sitemap";
    }

    @Override
    public NewsSourceChannel channel() {
        return NewsSourceChannel.SITEMAP;
    }

    @Override
    public List<NewsSourceResult> search(NewsSourceQuery query) {
        if (endpointDescriptors.isEmpty()) return List.of();
        long started = System.nanoTime();
        Map<String, NewsSourceResult> unique = new LinkedHashMap<>();
        int successful = 0;
        int failed = 0;
        int notModified = 0;
        for (NewsSourceEndpointDescriptor endpoint : endpointDescriptors) {
            NewsSourcePollBatch batch = poll(endpoint, NewsSourceValidators.EMPTY);
            if (batch.status() == NewsSourcePollBatch.Status.FAILED) {
                failed++;
                continue;
            }
            successful++;
            if (batch.status() == NewsSourcePollBatch.Status.NOT_MODIFIED) notModified++;
            addResults(unique, batch.results(), query);
        }
        lastCheckedAt = Instant.now();
        lastSuccessfulDocuments = successful;
        lastFailedDocuments = failed;
        lastNotModifiedDocuments = notModified;
        lastLatencyMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        int limit = query == null ? 10 : query.limit();
        return unique.values().stream()
                .sorted(Comparator.comparing(NewsSitemapSourceProvider::publicationInstant,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(NewsSourceResult::canonicalUrl,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(limit).toList();
    }

    @Override
    public List<NewsSourceEndpointDescriptor> configuredEndpoints() {
        return endpointDescriptors;
    }

    @Override
    public NewsSourcePollBatch poll(NewsSourceEndpointDescriptor endpoint,
                                    NewsSourceValidators validators) {
        requireOwnedEndpoint(endpoint);
        URI root = endpoint.url();
        Instant startedAt = Instant.now();
        PollStats stats = new PollStats();
        List<NewsSourceTransportRecord> transports = new ArrayList<>();
        Map<String, NewsSourceResult> unique = new LinkedHashMap<>();
        try {
            ParsedSitemap rootDocument = load(root, stats, transports, validators);
            addResults(unique, rootDocument.results(), null);
            AiNewsSourceCatalog.Endpoint rootCatalogEndpoint = catalogEndpoints.get(root);
            java.util.Set<URI> visited = new java.util.LinkedHashSet<>();
            visited.add(root);
            int traversed = 0;
            for (SitemapReference child : recentChildren(rootDocument.children(), null)) {
                if (traversed >= MAX_CHILD_SITEMAPS || unique.size() >= MAX_ITEMS_PER_ROOT) break;
                if (!visited.add(child.location())) continue;
                traversed++;
                try {
                    ParsedSitemap childDocument = load(child.location(), stats, transports,
                            NewsSourceValidators.EMPTY);
                    if (rootCatalogEndpoint != null) {
                        childDocument = childDocument.withCatalogEndpoint(
                                rootCatalogEndpoint, sourceCatalogVersion);
                    }
                    addResults(unique, childDocument.results(), null);
                } catch (Exception e) {
                    stats.failed++;
                    stats.firstError = stats.firstError == null ? e : stats.firstError;
                    if (transports.stream().noneMatch(item -> item.requestUrl()
                            .equals(child.location()))) {
                        transports.add(failedTransport(child.location(), startedAt, e));
                    }
                    log.warn("News sitemap child unavailable: sitemap={}, reason={}",
                            child.location(), e.getMessage());
                }
            }
            NewsSourcePollBatch.Status status = stats.failed > 0
                    ? NewsSourcePollBatch.Status.DEGRADED
                    : stats.successful > 0 && stats.notModified == stats.successful
                    ? NewsSourcePollBatch.Status.NOT_MODIFIED
                    : NewsSourcePollBatch.Status.SUCCESS;
            String errorCode = stats.firstError == null ? "" : errorCode(stats.firstError);
            String errorMessage = stats.firstError == null ? "" : safe(stats.firstError.getMessage());
            NewsSourcePollBatch batch = new NewsSourcePollBatch(endpoint, status, startedAt,
                    Instant.now(), List.copyOf(unique.values()), transports,
                    errorCode, errorMessage);
            observePoll(batch, stats);
            return batch;
        } catch (Exception e) {
            if (transports.stream().noneMatch(item -> item.requestUrl().equals(root))) {
                transports.add(failedTransport(root, startedAt, e));
            }
            NewsSourcePollBatch batch = new NewsSourcePollBatch(endpoint,
                    NewsSourcePollBatch.Status.FAILED, startedAt, Instant.now(), List.of(),
                    transports, errorCode(e), safe(e.getMessage()));
            stats.failed++;
            stats.firstError = e;
            observePoll(batch, stats);
            log.warn("News sitemap unavailable: sitemap={}, reason={}", root, e.getMessage());
            return batch;
        }
    }

    @Override
    public Optional<NewsSourceResult> fetch(URI url) {
        try {
            HttpResponse<byte[]> response = NewsSourceHttpSupport.get(client, url);
            if (response.statusCode() < 200 || response.statusCode() >= 300) return Optional.empty();
            String body = NewsSourceHttpSupport.text(response);
            String text = NewsSourceHttpSupport.stripHtml(body);
            return Optional.of(result(url.toString(), "", NewsSourceHttpSupport.truncate(text, 20_000),
                    response.statusCode(), "NEWS_SITEMAP_FETCH", Map.of()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public NewsSourceHealth health() {
        int configured = configuredSitemaps().size();
        if (configured == 0) {
            return NewsSourceHealth.disabled(providerId(),
                    "newsclaw.ai-news.sources.news-sitemaps.urls is empty");
        }
        if (lastCheckedAt == null) {
            return new NewsSourceHealth(providerId(), true, "configured",
                    configured + " sitemap(s) configured; reachability is checked during discovery",
                    Instant.now(), 0L);
        }
        boolean available = lastSuccessfulDocuments > 0;
        String status = !available ? "unhealthy" : lastFailedDocuments > 0 ? "degraded" : "healthy";
        return new NewsSourceHealth(providerId(), available, status,
                lastSuccessfulDocuments + " document(s) succeeded (" + lastNotModifiedDocuments
                        + " not modified); " + lastFailedDocuments + " failed",
                lastCheckedAt, lastLatencyMs);
    }

    ParsedSitemap parseSitemap(URI sitemapUri, HttpResponse<byte[]> response) throws Exception {
        return parseSitemap(sitemapUri, response.body() == null ? new byte[0] : response.body(),
                response.statusCode(), header(response, "etag"), header(response, "last-modified"));
    }

    private ParsedSitemap load(URI uri, PollStats stats,
                               List<NewsSourceTransportRecord> transports,
                               NewsSourceValidators validators) throws Exception {
        ConditionalNewsSourceHttpClient.FetchResponse fetched =
                conditionalClient.fetch(uri, validators);
        transports.add(fetched.transport());
        HttpResponse<byte[]> response = fetched.response();
        if (fetched.notModified()) {
            ParsedSitemap cached = cache.get(uri);
            if (cached == null) {
                ConditionalNewsSourceHttpClient.FetchResponse recovered =
                        conditionalClient.fetchUnconditional(uri);
                transports.add(recovered.transport());
                response = recovered.response();
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                ParsedSitemap parsed = parseSitemap(uri, response);
                cache.put(uri, parsed);
                stats.successful++;
                return parsed;
            }
            stats.successful++;
            stats.notModified++;
            return cached.revalidated(fetched);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        ParsedSitemap parsed = parseSitemap(uri, response);
        cache.put(uri, parsed);
        stats.successful++;
        return parsed;
    }

    private ParsedSitemap parseSitemap(URI sitemapUri, byte[] body, int status,
                                        String etag, String lastModified) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        org.w3c.dom.Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(body));

        List<SitemapReference> children = new ArrayList<>();
        org.w3c.dom.NodeList sitemapNodes = document.getElementsByTagNameNS("*", "sitemap");
        for (int i = 0; i < sitemapNodes.getLength(); i++) {
            org.w3c.dom.Node node = sitemapNodes.item(i);
            URI location = resolveHttp(sitemapUri, childText(node, "loc"));
            if (location != null) {
                children.add(new SitemapReference(location, childText(node, "lastmod")));
            }
        }

        List<NewsSourceResult> results = new ArrayList<>();
        org.w3c.dom.NodeList urlNodes = document.getElementsByTagNameNS("*", "url");
        for (int i = 0; i < urlNodes.getLength() && results.size() < MAX_ITEMS_PER_ROOT; i++) {
            org.w3c.dom.Node urlNode = urlNodes.item(i);
            URI article = resolveHttp(sitemapUri, childText(urlNode, "loc"));
            org.w3c.dom.Node newsNode = firstDescendant(urlNode, "news");
            if (article == null || newsNode == null) continue;
            String title = descendantText(newsNode, "title");
            String publishedRaw = descendantText(newsNode, "publication_date");
            if (title.isBlank() || publishedRaw.isBlank()) continue;

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sitemapUrl", sitemapUri.toString());
            AiNewsSourceCatalog.Endpoint catalogEndpoint = catalogEndpoints.get(sitemapUri);
            if (catalogEndpoint != null) {
                catalogEndpoint.addProvenance(metadata, sourceCatalogVersion);
            }
            metadata.put("publishedAtRaw", publishedRaw);
            Instant published = NewsSourceTimeParser.parseExact(publishedRaw);
            if (published != null) metadata.put("publishedAt", published.toString());
            if (NewsSourceTimeParser.dateOnly(publishedRaw)) {
                metadata.put("publishedAtPrecision", "DAY");
            }
            putIfPresent(metadata, "sourceModifiedAtRaw", childText(urlNode, "lastmod"));
            putIfPresent(metadata, "publicationName", descendantText(newsNode, "name"));
            putIfPresent(metadata, "language", descendantText(newsNode, "language"));
            putIfPresent(metadata, "etag", etag);
            putIfPresent(metadata, "lastModified", lastModified);
            results.add(result(article.toString(), title, title, status,
                    "NEWS_SITEMAP_DISCOVERY", metadata));
        }
        return new ParsedSitemap(List.copyOf(children), List.copyOf(results));
    }

    private void addResults(Map<String, NewsSourceResult> unique,
                            List<NewsSourceResult> candidates,
                            NewsSourceQuery query) {
        for (NewsSourceResult result : candidates) {
            Instant published = publicationInstant(result);
            if (query != null && query.since() != null && published != null
                    && published.isBefore(query.since())) continue;
            if (!NewsSourceTextMatcher.matches(result, query)) continue;
            String key = firstNonBlank(result.canonicalUrl(), result.sourceUrl());
            if (!key.isBlank()) unique.putIfAbsent(key, result);
        }
    }

    private static List<SitemapReference> recentChildren(List<SitemapReference> children,
                                                          NewsSourceQuery query) {
        if (query == null || query.since() == null) return children;
        LocalDate earliest = query.since().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        return children.stream().filter(child -> {
            if (child.lastModifiedRaw().isBlank()) return true;
            Instant exact = NewsSourceTimeParser.parseExact(child.lastModifiedRaw());
            if (exact != null) return !exact.isBefore(query.since().minusSeconds(86_400));
            try {
                return !LocalDate.parse(child.lastModifiedRaw()).isBefore(earliest);
            } catch (Exception ignored) {
                return true;
            }
        }).limit(MAX_CHILD_SITEMAPS).toList();
    }

    private NewsSourceResult result(String url, String title, String body, int status,
                                    String method, Map<String, Object> metadata) {
        String canonical = AiNewsEventService.canonicalUrl(url);
        String tier = sourceRegistry.isOfficialUrl(url) ? "official"
                : sourceRegistry.isTrustedMediaUrl(url) ? "media" : "community";
        return new NewsSourceResult(title, body, body,
                new NewsSourceProvenance(providerId(), tier, url, canonical, Instant.now(),
                        status, method, metadata));
    }

    private List<URI> configuredSitemaps() {
        return endpointDescriptors.stream().map(NewsSourceEndpointDescriptor::url).toList();
    }

    private static Instant publicationInstant(NewsSourceResult result) {
        if (result == null || result.provenance() == null) return null;
        Object value = result.provenance().metadata().get("publishedAt");
        return value == null ? null : NewsSourceTimeParser.parseExact(String.valueOf(value));
    }

    private static URI resolveHttp(URI base, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = base == null ? URI.create(value.trim()) : base.resolve(value.trim());
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) return null;
            return uri;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static org.w3c.dom.Node firstDescendant(org.w3c.dom.Node parent, String localName) {
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (localName.equalsIgnoreCase(localName(child))) return child;
            org.w3c.dom.Node nested = firstDescendant(child, localName);
            if (nested != null) return nested;
        }
        return null;
    }

    private static String descendantText(org.w3c.dom.Node parent, String localName) {
        org.w3c.dom.Node node = firstDescendant(parent, localName);
        return node == null || node.getTextContent() == null ? "" : node.getTextContent().trim();
    }

    private static String childText(org.w3c.dom.Node parent, String localName) {
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (localName.equalsIgnoreCase(localName(child))) {
                return child.getTextContent() == null ? "" : child.getTextContent().trim();
            }
        }
        return "";
    }

    private static String localName(org.w3c.dom.Node node) {
        if (node == null) return "";
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers() == null ? "" : response.headers().firstValue(name).orElse("");
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String mergeConfigured(String configured,
                                          List<AiNewsSourceCatalog.Endpoint> catalog) {
        List<String> values = new ArrayList<>();
        if (configured != null && !configured.isBlank()) values.add(configured);
        catalog.stream().map(endpoint -> endpoint.url().toString()).forEach(values::add);
        return String.join(",", values);
    }

    private static Map<URI, AiNewsSourceCatalog.Endpoint> endpointIndex(
            List<AiNewsSourceCatalog.Endpoint> endpoints) {
        Map<URI, AiNewsSourceCatalog.Endpoint> out = new LinkedHashMap<>();
        endpoints.forEach(endpoint -> out.put(endpoint.url(), endpoint));
        return Map.copyOf(out);
    }

    private static List<NewsSourceEndpointDescriptor> endpointDescriptors(
            AiNewsSourceRegistry sourceRegistry,
            String sitemapList,
            Map<URI, AiNewsSourceCatalog.Endpoint> catalogEndpoints,
            int catalogVersion) {
        Map<URI, NewsSourceEndpointDescriptor> out = new LinkedHashMap<>();
        for (String raw : (sitemapList == null ? "" : sitemapList).split(",")) {
            URI uri = resolveHttp(null, raw.trim());
            if (uri != null) out.put(uri, adHocDescriptor(sourceRegistry, uri));
        }
        catalogEndpoints.forEach((uri, endpoint) -> out.put(uri,
                catalogDescriptor(endpoint, catalogVersion)));
        return List.copyOf(out.values());
    }

    private static NewsSourceEndpointDescriptor adHocDescriptor(
            AiNewsSourceRegistry sourceRegistry, URI uri) {
        String sourceKey = sourceRegistry.publisherSourceKey(uri.toString())
                .orElse("operator-managed");
        return new NewsSourceEndpointDescriptor("adhoc-news-sitemap-"
                + NewsSourceHashing.shortHash(uri.normalize().toString()), 0, sourceKey,
                "news-sitemap", NewsSourceChannel.SITEMAP, "NEWS_SITEMAP", uri,
                List.of(), List.of(), 900, false, "operator_managed", "metadata_only",
                "operator_managed");
    }

    private static NewsSourceEndpointDescriptor catalogDescriptor(
            AiNewsSourceCatalog.Endpoint endpoint, int catalogVersion) {
        return new NewsSourceEndpointDescriptor(endpoint.endpointId(), catalogVersion,
                endpoint.sourceKey(), "news-sitemap", NewsSourceChannel.SITEMAP,
                "NEWS_SITEMAP", endpoint.url(), endpoint.languages(), endpoint.categories(),
                endpoint.pollIntervalSeconds(), endpoint.evidenceEligible(),
                endpoint.rightsStatus(), endpoint.rawRetention(), endpoint.robotsStatus());
    }

    private void requireOwnedEndpoint(NewsSourceEndpointDescriptor endpoint) {
        if (endpoint == null || endpointDescriptors.stream()
                .noneMatch(item -> item.endpointKey().equals(endpoint.endpointKey())
                        && item.url().equals(endpoint.url()))) {
            throw new IllegalArgumentException("News Sitemap endpoint is not configured by this provider");
        }
    }

    private static NewsSourceTransportRecord failedTransport(URI uri, Instant startedAt,
                                                              Exception error) {
        return new NewsSourceTransportRecord(uri, uri, null, "", "", "", "", null,
                new byte[0], false, false, startedAt, Instant.now(), errorCode(error),
                safe(error.getMessage()));
    }

    private void observePoll(NewsSourcePollBatch batch, PollStats stats) {
        lastCheckedAt = batch.finishedAt();
        lastSuccessfulDocuments = stats.successful;
        lastFailedDocuments = stats.failed;
        lastNotModifiedDocuments = stats.notModified;
        lastLatencyMs = java.time.Duration.between(batch.startedAt(), batch.finishedAt()).toMillis();
    }

    private static String errorCode(Exception error) {
        if (error instanceof java.net.http.HttpTimeoutException) return "TIMEOUT";
        if (error instanceof SecurityException || error instanceof IllegalArgumentException) {
            return "REJECTED";
        }
        if (error instanceof javax.xml.parsers.ParserConfigurationException
                || error instanceof org.xml.sax.SAXException) return "PARSE_ERROR";
        return "TRANSPORT_ERROR";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    record ParsedSitemap(List<SitemapReference> children, List<NewsSourceResult> results) {
        ParsedSitemap withCatalogEndpoint(AiNewsSourceCatalog.Endpoint endpoint,
                                          int catalogVersion) {
            List<NewsSourceResult> attributed = results.stream().map(result -> {
                NewsSourceProvenance old = result.provenance();
                Map<String, Object> metadata = new LinkedHashMap<>(old.metadata());
                endpoint.addProvenance(metadata, catalogVersion);
                return new NewsSourceResult(result.title(), result.snippet(), result.content(),
                        new NewsSourceProvenance(old.providerId(), old.sourceTier(), old.sourceUrl(),
                                old.canonicalUrl(), old.fetchedAt(), old.httpStatus(),
                                old.retrievalMethod(), metadata));
            }).toList();
            return new ParsedSitemap(children, attributed);
        }

        ParsedSitemap revalidated(ConditionalNewsSourceHttpClient.FetchResponse fetched) {
            List<NewsSourceResult> refreshed = results.stream().map(result -> {
                NewsSourceProvenance old = result.provenance();
                Map<String, Object> metadata = new LinkedHashMap<>(old.metadata());
                metadata.put("revalidated", true);
                metadata.put("revalidatedAt", fetched.observedAt().toString());
                putIfPresent(metadata, "etag", fetched.etag());
                putIfPresent(metadata, "lastModified", fetched.lastModified());
                return new NewsSourceResult(result.title(), result.snippet(), result.content(),
                        new NewsSourceProvenance(old.providerId(), old.sourceTier(), old.sourceUrl(),
                                old.canonicalUrl(), fetched.observedAt(), 304,
                                "NEWS_SITEMAP_REVALIDATED", metadata));
            }).toList();
            return new ParsedSitemap(children, refreshed);
        }
    }

    record SitemapReference(URI location, String lastModifiedRaw) {
        SitemapReference {
            lastModifiedRaw = lastModifiedRaw == null ? "" : lastModifiedRaw.trim();
        }
    }

    private static final class PollStats {
        private int successful;
        private int failed;
        private int notModified;
        private Exception firstError;
    }
}

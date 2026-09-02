package vip.newsclaw.news.source;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standards-oriented RSS/Atom adapter backed by ROME.
 *
 * <p>Feed metadata remains an untrusted discovery hint for callers. A separate
 * capture-time service may attest its exact timestamp only from the persisted
 * ledger after endpoint governance, canonical URL, publisher ownership and raw
 * transport integrity all pass; the article body still comes from capture.</p>
 */
@Component
@Slf4j
public class RssNewsSourceProvider implements ScheduledNewsSourceProvider {

    static final int MAX_ITEMS_PER_FEED = 500;

    private final AiNewsSourceRegistry sourceRegistry;
    private final ConditionalNewsSourceHttpClient conditionalClient;
    private final HttpClient client;
    private final String feedList;
    private final Map<URI, AiNewsSourceCatalog.Endpoint> catalogEndpoints;
    private final int sourceCatalogVersion;
    private final List<NewsSourceEndpointDescriptor> endpointDescriptors;
    private final Map<URI, List<NewsSourceResult>> cachedFeeds = new ConcurrentHashMap<>();
    /** Latest terminal poll outcome per configured endpoint. */
    private final Map<String, PollObservation> pollObservations = new ConcurrentHashMap<>();

    @Autowired
    public RssNewsSourceProvider(AiNewsSourceRegistry sourceRegistry,
                                 AiNewsSourceCatalog sourceCatalog,
                                 @Value("${newsclaw.ai-news.sources.rss.feeds:}") String feedList) {
        this(sourceRegistry, mergeConfigured(feedList,
                        sourceCatalog.enabled(AiNewsSourceCatalog.EndpointAdapter.FEED)),
                HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(10)).build(),
                endpointIndex(sourceCatalog.enabled(AiNewsSourceCatalog.EndpointAdapter.FEED)),
                sourceCatalog.version());
    }

    RssNewsSourceProvider(AiNewsSourceRegistry sourceRegistry, String feedList,
                          HttpClient client) {
        this(sourceRegistry, feedList, client, Map.of(), 0);
    }

    private RssNewsSourceProvider(AiNewsSourceRegistry sourceRegistry, String feedList,
                                  HttpClient client,
                                  Map<URI, AiNewsSourceCatalog.Endpoint> catalogEndpoints,
                                  int sourceCatalogVersion) {
        this.sourceRegistry = sourceRegistry;
        this.feedList = feedList == null ? "" : feedList;
        this.client = client;
        this.conditionalClient = new ConditionalNewsSourceHttpClient(client);
        this.catalogEndpoints = Map.copyOf(catalogEndpoints);
        this.sourceCatalogVersion = sourceCatalogVersion;
        this.endpointDescriptors = endpointDescriptors(sourceRegistry, this.feedList,
                this.catalogEndpoints, sourceCatalogVersion);
    }

    @Override
    public String providerId() {
        return "rss";
    }

    @Override
    public NewsSourceChannel channel() {
        return NewsSourceChannel.FEED;
    }

    @Override
    public List<NewsSourceResult> search(NewsSourceQuery query) {
        if (endpointDescriptors.isEmpty()) return List.of();
        Map<String, NewsSourceResult> unique = new LinkedHashMap<>();
        for (NewsSourceEndpointDescriptor endpoint : endpointDescriptors) {
            NewsSourcePollBatch batch = poll(endpoint, NewsSourceValidators.EMPTY);
            if (batch.status() == NewsSourcePollBatch.Status.FAILED) {
                continue;
            }
            for (NewsSourceResult result : batch.results()) {
                Instant orderingTime = orderingInstant(result);
                if (query != null && query.since() != null && orderingTime != null
                        && orderingTime.isBefore(query.since())) continue;
                if (!NewsSourceTextMatcher.matches(result, query)) continue;
                String key = firstNonBlank(result.canonicalUrl(), result.sourceUrl());
                if (!key.isBlank()) unique.putIfAbsent(key, result);
            }
        }
        int limit = query == null ? 10 : query.limit();
        return unique.values().stream()
                .sorted(Comparator.comparing(RssNewsSourceProvider::orderingInstant,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(result -> Optional.ofNullable(result.canonicalUrl())
                                .orElse("")))
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
        URI feed = endpoint.url();
        Instant startedAt = Instant.now();
        List<NewsSourceTransportRecord> transports = new ArrayList<>();
        try {
            ConditionalNewsSourceHttpClient.FetchResponse fetched =
                    conditionalClient.fetch(feed, validators);
            transports.add(fetched.transport());
            HttpResponse<byte[]> response = fetched.response();
            List<NewsSourceResult> feedResults;
            NewsSourcePollBatch.Status status;
            if (fetched.notModified()) {
                feedResults = cachedFeeds.get(feed);
                if (feedResults == null) {
                    // Validators survive process restarts, parsed Java objects do not.
                    // Recover the representation immediately instead of treating a
                    // perfectly valid 304 as permanent data loss.
                    ConditionalNewsSourceHttpClient.FetchResponse recovered =
                            conditionalClient.fetchUnconditional(feed);
                    transports.add(recovered.transport());
                    response = recovered.response();
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return failedBatch(endpoint, startedAt, transports,
                                "HTTP_STATUS", "HTTP " + response.statusCode());
                    }
                    feedResults = parseFeed(feed, response, null);
                    cachedFeeds.put(feed, feedResults);
                    status = NewsSourcePollBatch.Status.SUCCESS;
                } else {
                    feedResults = revalidated(feedResults, fetched);
                    status = NewsSourcePollBatch.Status.NOT_MODIFIED;
                }
            } else if (response.statusCode() >= 200 && response.statusCode() < 300) {
                feedResults = parseFeed(feed, response, null);
                cachedFeeds.put(feed, feedResults);
                status = NewsSourcePollBatch.Status.SUCCESS;
            } else {
                return failedBatch(endpoint, startedAt, transports,
                        "HTTP_STATUS", "HTTP " + response.statusCode());
            }
            NewsSourcePollBatch batch = new NewsSourcePollBatch(endpoint, status,
                    startedAt, Instant.now(), feedResults, transports, "", "");
            observePoll(batch);
            return batch;
        } catch (Exception e) {
            if (transports.isEmpty()) {
                transports.add(new NewsSourceTransportRecord(
                        feed, feed, null, "", "", "", "", null, new byte[0],
                        false, false, startedAt, Instant.now(), errorCode(e), safe(e.getMessage())));
            }
            NewsSourcePollBatch batch = failedBatch(endpoint, startedAt, transports,
                    errorCode(e), safe(e.getMessage()));
            log.warn("RSS feed unavailable: feed={}, reason={}", feed, e.getMessage());
            return batch;
        }
    }

    @Override
    public Optional<NewsSourceResult> fetch(URI url) {
        try {
            HttpResponse<byte[]> response = NewsSourceHttpSupport.get(client, url);
            if (response.statusCode() < 200 || response.statusCode() >= 300) return Optional.empty();
            String body = NewsSourceHttpSupport.text(response);
            String title = extractHtmlTitle(body);
            String text = NewsSourceHttpSupport.stripHtml(body);
            return Optional.of(result(url.toString(), title,
                    NewsSourceHttpSupport.truncate(text, 20_000), response.statusCode(),
                    "RSS_FETCH", Map.of()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public NewsSourceHealth health() {
        int configured = configuredFeeds().size();
        if (configured == 0) {
            return NewsSourceHealth.disabled(providerId(),
                    "newsclaw.ai-news.sources.rss.feeds is empty");
        }
        List<PollObservation> observed = endpointDescriptors.stream()
                .map(endpoint -> pollObservations.get(endpoint.endpointKey()))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (observed.isEmpty()) {
            return new NewsSourceHealth(providerId(), true, "configured",
                    configured + " feed(s) configured; reachability is checked during discovery",
                    Instant.now(), 0L);
        }
        int successful = (int) observed.stream()
                .filter(value -> value.status() != NewsSourcePollBatch.Status.FAILED).count();
        int failed = observed.size() - successful;
        int notModified = (int) observed.stream()
                .filter(value -> value.status() == NewsSourcePollBatch.Status.NOT_MODIFIED).count();
        int notYetChecked = configured - observed.size();
        boolean available = successful > 0;
        String status = !available ? "unhealthy"
                : failed > 0 || notYetChecked > 0 ? "degraded" : "healthy";
        Instant checkedAt = observed.stream().map(PollObservation::checkedAt)
                .max(Instant::compareTo).orElseGet(Instant::now);
        long latencyMs = observed.stream().mapToLong(PollObservation::latencyMs)
                .max().orElse(0L);
        String unchecked = notYetChecked > 0
                ? "; " + notYetChecked + " not yet checked" : "";
        return new NewsSourceHealth(providerId(), available, status,
                successful + " feed(s) last succeeded (" + notModified
                        + " not modified); " + failed + " last failed" + unchecked,
                checkedAt, latencyMs);
    }

    List<NewsSourceResult> parseFeed(URI feedUri, HttpResponse<byte[]> response,
                                     NewsSourceQuery query) throws Exception {
        byte[] body = response.body() == null ? new byte[0] : response.body();
        List<TemporalFields> temporalFields = secureTemporalFields(body);
        SyndFeed feed;
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(body))) {
            feed = new SyndFeedInput().build(reader);
        }

        List<NewsSourceResult> out = new ArrayList<>();
        List<SyndEntry> entries = feed.getEntries() == null ? List.of() : feed.getEntries();
        for (int i = 0; i < entries.size() && out.size() < MAX_ITEMS_PER_FEED; i++) {
            SyndEntry entry = entries.get(i);
            String title = safe(entry.getTitle());
            String resolvedLink = resolveItemLink(feedUri,
                    firstNonBlank(entry.getLink(), entry.getUri()));
            if (title.isBlank() || resolvedLink.isBlank()) continue;

            TemporalFields times = i < temporalFields.size()
                    ? temporalFields.get(i) : TemporalFields.EMPTY;
            String description = entryDescription(entry);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("feedUrl", feedUri.toString());
            AiNewsSourceCatalog.Endpoint catalogEndpoint = catalogEndpoints.get(feedUri);
            if (catalogEndpoint != null) {
                catalogEndpoint.addProvenance(metadata, sourceCatalogVersion);
            }
            putIfPresent(metadata, "feedId", feed.getUri());
            putIfPresent(metadata, "feedEntryId", entry.getUri());
            putIfPresent(metadata, "publishedAtRaw", times.publishedRaw());
            putIfPresent(metadata, "updatedAtRaw", times.updatedRaw());
            putTimestamp(metadata, "publishedAt", times.publishedRaw());
            putTimestamp(metadata, "updatedAt", times.updatedRaw());
            if (NewsSourceTimeParser.dateOnly(times.publishedRaw())) {
                metadata.put("publishedAtPrecision", "DAY");
            }
            List<String> authors = entry.getAuthors() == null ? List.of()
                    : entry.getAuthors().stream().map(author -> safe(author.getName()))
                    .filter(value -> !value.isBlank()).distinct().toList();
            List<String> categories = entry.getCategories() == null ? List.of()
                    : entry.getCategories().stream().map(category -> safe(category.getName()))
                    .filter(value -> !value.isBlank()).distinct().toList();
            if (!authors.isEmpty()) metadata.put("authors", authors);
            if (!categories.isEmpty()) metadata.put("categories", categories);
            putResponseValidator(metadata, response, "etag", "etag");
            putResponseValidator(metadata, response, "last-modified", "lastModified");
            out.add(result(resolvedLink, title, NewsSourceHttpSupport.truncate(
                    NewsSourceHttpSupport.stripHtml(description), 20_000),
                    response.statusCode(), "RSS_SEARCH", metadata));
        }
        return List.copyOf(out);
    }

    private static List<NewsSourceResult> revalidated(
            List<NewsSourceResult> cached,
            ConditionalNewsSourceHttpClient.FetchResponse fetched) {
        List<NewsSourceResult> out = new ArrayList<>(cached.size());
        for (NewsSourceResult result : cached) {
            Map<String, Object> metadata = new LinkedHashMap<>(result.provenance().metadata());
            metadata.put("revalidated", true);
            metadata.put("revalidatedAt", fetched.observedAt().toString());
            putIfPresent(metadata, "etag", fetched.etag());
            putIfPresent(metadata, "lastModified", fetched.lastModified());
            NewsSourceProvenance old = result.provenance();
            out.add(new NewsSourceResult(result.title(), result.snippet(), result.content(),
                    new NewsSourceProvenance(old.providerId(), old.sourceTier(), old.sourceUrl(),
                            old.canonicalUrl(), fetched.observedAt(), 304,
                            "RSS_ATOM_REVALIDATED", metadata)));
        }
        return List.copyOf(out);
    }

    private NewsSourceResult result(String url, String title, String body, int status,
                                    String method, Map<String, Object> metadata) {
        String canonical = AiNewsEventService.canonicalUrl(url);
        String tier = sourceRegistry.isOfficialUrl(url) ? "official"
                : sourceRegistry.isTrustedMediaUrl(url) ? "media" : "community";
        NewsSourceProvenance provenance = new NewsSourceProvenance(providerId(), tier, url, canonical,
                Instant.now(), status, method, metadata);
        return new NewsSourceResult(title, body, body, provenance);
    }

    static Instant parsePublicationInstant(String value) {
        return NewsSourceTimeParser.parseExact(value);
    }

    private static Instant orderingInstant(NewsSourceResult result) {
        if (result == null || result.provenance() == null) return null;
        Object value = result.provenance().metadata().get("publishedAt");
        if (value == null) value = result.provenance().metadata().get("updatedAt");
        return value == null ? null : NewsSourceTimeParser.parseExact(String.valueOf(value));
    }

    static boolean matchesQuery(NewsSourceResult result, NewsSourceQuery query) {
        return NewsSourceTextMatcher.matches(result, query);
    }

    private List<URI> configuredFeeds() {
        return endpointDescriptors.stream().map(NewsSourceEndpointDescriptor::url).toList();
    }

    private static List<TemporalFields> secureTemporalFields(byte[] body) throws Exception {
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
        org.w3c.dom.NodeList nodes = document.getElementsByTagNameNS("*", "item");
        if (nodes.getLength() == 0) nodes = document.getElementsByTagNameNS("*", "entry");
        List<TemporalFields> out = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Node node = nodes.item(i);
            String published = firstNonBlank(childText(node, "pubDate"),
                    childText(node, "published"), childText(node, "date"));
            String updated = childText(node, "updated");
            out.add(new TemporalFields(published, updated));
        }
        return List.copyOf(out);
    }

    private static String childText(org.w3c.dom.Node parent, String localName) {
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            String local = child.getLocalName();
            if (localName.equalsIgnoreCase(local == null ? child.getNodeName() : local)) {
                return safe(child.getTextContent());
            }
        }
        return "";
    }

    private static String entryDescription(SyndEntry entry) {
        SyndContent description = entry.getDescription();
        if (description != null && description.getValue() != null
                && !description.getValue().isBlank()) return description.getValue();
        if (entry.getContents() != null) {
            for (SyndContent content : entry.getContents()) {
                if (content != null && content.getValue() != null
                        && !content.getValue().isBlank()) return content.getValue();
            }
        }
        return "";
    }

    private static void putTimestamp(Map<String, Object> metadata, String key, String raw) {
        Instant parsed = NewsSourceTimeParser.parseExact(raw);
        if (parsed != null) metadata.put(key, parsed.toString());
    }

    private static void putResponseValidator(Map<String, Object> metadata,
                                             HttpResponse<byte[]> response,
                                             String header, String key) {
        if (response.headers() == null) return;
        response.headers().firstValue(header).ifPresent(value -> putIfPresent(metadata, key, value));
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private static String resolveItemLink(URI feed, String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI resolved = feed.resolve(value.trim());
            if (resolved.getHost() == null || !("https".equalsIgnoreCase(resolved.getScheme())
                    || "http".equalsIgnoreCase(resolved.getScheme()))) return "";
            return resolved.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String extractHtmlTitle(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?is)<title[^>]*>(.*?)</title>").matcher(body == null ? "" : body);
        return matcher.find() ? NewsSourceHttpSupport.stripHtml(matcher.group(1)) : "";
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
            String feedList,
            Map<URI, AiNewsSourceCatalog.Endpoint> catalogEndpoints,
            int catalogVersion) {
        Map<URI, NewsSourceEndpointDescriptor> out = new LinkedHashMap<>();
        for (String raw : (feedList == null ? "" : feedList).split(",")) {
            try {
                URI uri = URI.create(raw.trim());
                if (!raw.isBlank() && uri.isAbsolute() && uri.getHost() != null
                        && ("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))) {
                    out.put(uri, adHocDescriptor(sourceRegistry, uri));
                }
            } catch (Exception ignored) {
                // Invalid deployment input stays disabled rather than creating a row.
            }
        }
        catalogEndpoints.forEach((uri, endpoint) -> out.put(uri,
                catalogDescriptor(endpoint, catalogVersion)));
        return List.copyOf(out.values());
    }

    private static NewsSourceEndpointDescriptor adHocDescriptor(
            AiNewsSourceRegistry sourceRegistry, URI uri) {
        String sourceKey = sourceRegistry.publisherSourceKey(uri.toString())
                .orElse("operator-managed");
        return new NewsSourceEndpointDescriptor("adhoc-rss-"
                + NewsSourceHashing.shortHash(uri.normalize().toString()), 0, sourceKey,
                "rss", NewsSourceChannel.FEED, "FEED", uri, List.of(), List.of(),
                900, false, "operator_managed", "metadata_only", "operator_managed");
    }

    private static NewsSourceEndpointDescriptor catalogDescriptor(
            AiNewsSourceCatalog.Endpoint endpoint, int catalogVersion) {
        return new NewsSourceEndpointDescriptor(endpoint.endpointId(), catalogVersion,
                endpoint.sourceKey(), "rss", NewsSourceChannel.FEED, "FEED", endpoint.url(),
                endpoint.languages(), endpoint.categories(), endpoint.pollIntervalSeconds(),
                endpoint.evidenceEligible(), endpoint.rightsStatus(), endpoint.rawRetention(),
                endpoint.robotsStatus());
    }

    private void requireOwnedEndpoint(NewsSourceEndpointDescriptor endpoint) {
        if (endpoint == null || endpointDescriptors.stream()
                .noneMatch(item -> item.endpointKey().equals(endpoint.endpointKey())
                        && item.url().equals(endpoint.url()))) {
            throw new IllegalArgumentException("RSS endpoint is not configured by this provider");
        }
    }

    private NewsSourcePollBatch failedBatch(NewsSourceEndpointDescriptor endpoint,
                                            Instant startedAt,
                                            List<NewsSourceTransportRecord> transports,
                                            String code,
                                            String message) {
        NewsSourcePollBatch batch = new NewsSourcePollBatch(endpoint, NewsSourcePollBatch.Status.FAILED,
                startedAt, Instant.now(), List.of(), List.copyOf(transports), code, message);
        observePoll(batch);
        return batch;
    }

    private void observePoll(NewsSourcePollBatch batch) {
        PollObservation current = new PollObservation(batch.status(), batch.finishedAt(),
                java.time.Duration.between(batch.startedAt(), batch.finishedAt()).toMillis());
        pollObservations.compute(batch.endpoint().endpointKey(), (key, previous) ->
                previous == null || !previous.checkedAt().isAfter(current.checkedAt())
                        ? current : previous);
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

    private record TemporalFields(String publishedRaw, String updatedRaw) {
        private static final TemporalFields EMPTY = new TemporalFields("", "");
    }

    private record PollObservation(NewsSourcePollBatch.Status status,
                                   Instant checkedAt,
                                   long latencyMs) {
    }
}

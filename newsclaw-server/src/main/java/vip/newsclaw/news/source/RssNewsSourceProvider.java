package vip.newsclaw.news.source;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Generic RSS/Atom adapter. Feeds are configuration, not hard-coded facts. */
@Component
public class RssNewsSourceProvider implements NewsSourceProvider {

    private final AiNewsSourceRegistry sourceRegistry;
    private final HttpClient client;
    private final String feedList;

    public RssNewsSourceProvider(AiNewsSourceRegistry sourceRegistry,
                                 @Value("${newsclaw.ai-news.sources.rss.feeds:}") String feedList) {
        this.sourceRegistry = sourceRegistry;
        this.feedList = feedList == null ? "" : feedList;
        this.client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    @Override
    public String providerId() {
        return "rss";
    }

    @Override
    public List<NewsSourceResult> search(NewsSourceQuery query) {
        List<URI> feeds = configuredFeeds();
        if (feeds.isEmpty()) return List.of();
        List<NewsSourceResult> out = new ArrayList<>();
        String needle = query == null ? "" : query.query().toLowerCase(Locale.ROOT);
        for (URI feed : feeds) {
            try {
                HttpResponse<byte[]> response = NewsSourceHttpSupport.get(client, feed);
                if (response.statusCode() < 200 || response.statusCode() >= 300) continue;
                for (NewsSourceResult result : parseFeed(feed, response, query)) {
                    if (needle.isBlank() || (result.title() + " " + result.snippet())
                            .toLowerCase(Locale.ROOT).contains(needle)) {
                        out.add(result);
                    }
                    if (out.size() >= (query == null ? 10 : query.limit())) return List.copyOf(out);
                }
            } catch (Exception ignored) {
                // One unavailable feed must not hide other configured feeds.
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<NewsSourceResult> fetch(URI url) {
        try {
            HttpResponse<byte[]> response = NewsSourceHttpSupport.get(client, url);
            if (response.statusCode() < 200 || response.statusCode() >= 300) return Optional.empty();
            String body = NewsSourceHttpSupport.text(response);
            String title = extractHtmlTitle(body);
            String text = NewsSourceHttpSupport.stripHtml(body);
            return Optional.of(result(url.toString(), title, NewsSourceHttpSupport.truncate(text, 20_000),
                    response.statusCode(), "RSS_FETCH"));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Override
    public NewsSourceHealth health() {
        return configuredFeeds().isEmpty()
                ? NewsSourceHealth.disabled(providerId(), "newsclaw.news.sources.rss.feeds is empty")
                : NewsSourceHealth.healthy(providerId(), 0L);
    }

    List<NewsSourceResult> parseFeed(URI feed, HttpResponse<byte[]> response, NewsSourceQuery query)
            throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body()));
        var nodes = document.getElementsByTagName("item").getLength() > 0
                ? document.getElementsByTagName("item") : document.getElementsByTagName("entry");
        List<NewsSourceResult> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            String title = childText(node, "title");
            String description = childText(node, "description");
            if (description.isBlank()) description = childText(node, "summary");
            String link = childText(node, "link");
            if (link.isBlank()) link = atomHref(node);
            if (link.isBlank()) link = feed.toString();
            out.add(result(link, title, NewsSourceHttpSupport.truncate(
                    NewsSourceHttpSupport.stripHtml(description), 20_000),
                    response.statusCode(), "RSS_SEARCH"));
        }
        return out;
    }

    private NewsSourceResult result(String url, String title, String body, int status, String method) {
        String canonical = AiNewsEventService.canonicalUrl(url);
        String tier = sourceRegistry.isOfficialUrl(url) ? "official"
                : sourceRegistry.isTrustedMediaUrl(url) ? "media" : "community";
        NewsSourceProvenance provenance = new NewsSourceProvenance(providerId(), tier, url, canonical,
                Instant.now(), status, method, Map.of());
        return new NewsSourceResult(title, body, body, provenance);
    }

    private List<URI> configuredFeeds() {
        List<URI> out = new ArrayList<>();
        for (String raw : feedList.split(",")) {
            try {
                if (!raw.isBlank()) out.add(URI.create(raw.trim()));
            } catch (Exception ignored) {
                // Bad configuration is reflected by the missing feed.
            }
        }
        return out;
    }

    private static String childText(org.w3c.dom.Node parent, String name) {
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            var child = children.item(i);
            if (name.equalsIgnoreCase(child.getNodeName())
                    || child.getNodeName().toLowerCase(Locale.ROOT).endsWith(":" + name)) {
                return child.getTextContent() == null ? "" : child.getTextContent().trim();
            }
        }
        return "";
    }

    private static String atomHref(org.w3c.dom.Node parent) {
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            var child = children.item(i);
            if (!"link".equalsIgnoreCase(child.getNodeName())
                    && !child.getNodeName().toLowerCase(Locale.ROOT).endsWith(":link")) {
                continue;
            }
            var href = child.getAttributes() == null
                    ? null : child.getAttributes().getNamedItem("href");
            if (href != null && href.getNodeValue() != null) return href.getNodeValue().trim();
        }
        return "";
    }

    private static String extractHtmlTitle(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?is)<title[^>]*>(.*?)</title>").matcher(body == null ? "" : body);
        return matcher.find() ? NewsSourceHttpSupport.stripHtml(matcher.group(1)) : "";
    }
}

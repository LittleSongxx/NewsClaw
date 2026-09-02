package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic extraction for a bounded, read-only AI-news source capture.
 *
 * <p>Only explicit publication metadata carrying a timezone is accepted. A
 * date guessed from prose, a search snippet, or a timezone-less local value is
 * deliberately not promoted to {@code sourcePublishedAt}; those pages must go
 * through review instead of silently entering a "latest news" window.</p>
 */
@Component
public class AiNewsSourceDocumentParser {

    private static final int MAX_TITLE_CHARS = 512;
    private static final List<String> PUBLISHED_META_SELECTORS = List.of(
            "meta[property=article:published_time]",
            "meta[property=og:published_time]",
            "meta[name=datePublished]",
            "meta[name=publishdate]",
            "meta[name=pubdate]",
            "meta[name=publication_date]",
            "meta[name=parsely-pub-date]",
            "meta[name=sailthru.date]",
            "meta[name=DC.date.issued]",
            "meta[itemprop=datePublished]",
            "time[itemprop=datePublished][datetime]"
    );
    private static final java.util.Set<String> ARTICLE_TYPES = java.util.Set.of(
            "article", "newsarticle", "blogposting", "report", "techarticle",
            "analysisnewsarticle", "backgroundnewsarticle", "opinionnewsarticle",
            "reviewnewsarticle", "scholarlyarticle");
    private static final String JSON_EXTRACTOR_HASH = sha256("json_recursive_value_walk@1");
    private static final String PLAIN_TEXT_EXTRACTOR_HASH = sha256("plain_text_nfkc@1");

    private final ObjectMapper objectMapper;
    private final AiNewsMainContentExtractor mainContentExtractor;

    @Autowired
    public AiNewsSourceDocumentParser(ObjectMapper objectMapper,
                                      AiNewsMainContentExtractor mainContentExtractor) {
        this.objectMapper = objectMapper;
        this.mainContentExtractor = mainContentExtractor;
    }

    /** Unit-test/standalone compatibility constructor with explicitly marked fallback extraction. */
    AiNewsSourceDocumentParser(ObjectMapper objectMapper) {
        this(objectMapper, AiNewsSourceDocumentParser::legacyFallback);
    }

    public ParsedDocument parse(String body, String contentType, String baseUrl) {
        String safeBody = body == null ? "" : body;
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lowerType.contains("json") || looksLikeJson(safeBody)) {
            return parseJson(safeBody);
        }
        if (lowerType.contains("text/plain") || !looksLikeHtml(safeBody)) {
            return new ParsedDocument("来源页面", normalizeText(safeBody), null, null, null,
                    "plain_text", "1", PLAIN_TEXT_EXTRACTOR_HASH, false, null);
        }
        return parseHtmlOrText(safeBody, baseUrl);
    }

    private ParsedDocument parseHtmlOrText(String body, String baseUrl) {
        Document document = Jsoup.parse(body, baseUrl == null ? "" : baseUrl);
        PublicationMetadata publication = publicationFromJsonLd(document);
        if (publication == null) publication = publicationFromMarkup(document);

        String title = firstNonBlank(
                attribute(document.selectFirst("meta[property=og:title]"), "content"),
                document.title());
        AiNewsContentExtractionResult extraction = mainContentExtractor.extract(body, baseUrl);
        title = firstNonBlank(title, extraction.title(), "来源页面");
        String text = normalizeText(extraction.text());
        return new ParsedDocument(trim(title, MAX_TITLE_CHARS), text,
                publication == null ? null : publication.publishedAtUtc(),
                publication == null ? null : publication.raw(),
                publication == null ? null : publication.method(),
                extraction.extractorName(), extraction.extractorVersion(),
                extraction.extractorConfigHash(), extraction.fallback(), extraction.warning());
    }

    private ParsedDocument parseJson(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            PublicationMetadata publication = publicationFromJson(root, "JSON");
            String title = findFirstText(root, List.of("headline", "name", "title"));
            String text = normalizeText(jsonText(root));
            return new ParsedDocument(trim(firstNonBlank(title, "来源数据"), MAX_TITLE_CHARS),
                    text, publication == null ? null : publication.publishedAtUtc(),
                    publication == null ? null : publication.raw(),
                    publication == null ? null : publication.method(),
                    "json_value_walk", "1", JSON_EXTRACTOR_HASH, false, null);
        } catch (Exception ignored) {
            return new ParsedDocument("来源数据", normalizeText(body), null, null, null,
                    "plain_text", "1", PLAIN_TEXT_EXTRACTOR_HASH, true,
                    "invalid_json_fallback");
        }
    }

    private PublicationMetadata publicationFromJsonLd(Document document) {
        List<JsonNode> roots = new ArrayList<>();
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                roots.add(objectMapper.readTree(script.data()));
            } catch (Exception ignored) {
                // A malformed analytics/schema block must not prevent parsing
                // another valid JSON-LD block or explicit HTML metadata.
            }
        }
        // JSON-LD pages commonly include Organization/WebSite/Breadcrumb data
        // before the actual article. Only an article-typed node may contribute
        // a nested publication date; otherwise an old organization date can
        // silently make a fresh story appear stale (or vice versa).
        for (JsonNode root : roots) {
            PublicationMetadata metadata = publicationFromTypedJson(root, "JSON_LD");
            if (metadata != null) return metadata;
        }
        // Tolerate a minimal top-level JSON-LD object that omitted @type, but
        // never recurse through arbitrary unrelated nested objects here.
        for (JsonNode root : roots) {
            PublicationMetadata metadata = publicationFromTopLevelJson(root, "JSON_LD");
            if (metadata != null) return metadata;
        }
        return null;
    }

    private PublicationMetadata publicationFromTypedJson(JsonNode node, String methodPrefix) {
        if (node == null) return null;
        if (node.isObject()) {
            if (isArticleType(node.get("@type"))) {
                PublicationMetadata metadata = publicationFromObject(node, methodPrefix);
                if (metadata != null) return metadata;
            }
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                PublicationMetadata metadata = publicationFromTypedJson(children.next(), methodPrefix);
                if (metadata != null) return metadata;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                PublicationMetadata metadata = publicationFromTypedJson(child, methodPrefix);
                if (metadata != null) return metadata;
            }
        }
        return null;
    }

    private PublicationMetadata publicationFromTopLevelJson(JsonNode node, String methodPrefix) {
        if (node == null) return null;
        if (node.isObject()) {
            return node.has("@type") ? null : publicationFromObject(node, methodPrefix);
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (!child.isObject() || child.has("@type")) continue;
                PublicationMetadata metadata = publicationFromObject(child, methodPrefix);
                if (metadata != null) return metadata;
            }
        }
        return null;
    }

    private static boolean isArticleType(JsonNode type) {
        if (type == null) return false;
        if (type.isTextual()) return ARTICLE_TYPES.contains(type.asText().toLowerCase(Locale.ROOT));
        if (type.isArray()) {
            for (JsonNode item : type) if (isArticleType(item)) return true;
        }
        return false;
    }

    private static PublicationMetadata publicationFromObject(JsonNode node, String methodPrefix) {
        if (node == null || !node.isObject()) return null;
        for (String field : List.of("datePublished", "dateCreated")) {
            JsonNode candidate = node.get(field);
            if (candidate != null && candidate.isValueNode()) {
                PublicationMetadata parsed = parsePublication(candidate.asText(),
                        methodPrefix + "_" + field.toUpperCase(Locale.ROOT));
                if (parsed != null) return parsed;
            }
        }
        return null;
    }

    private PublicationMetadata publicationFromJson(JsonNode node, String methodPrefix) {
        if (node == null) return null;
        if (node.isObject()) {
            PublicationMetadata direct = publicationFromObject(node, methodPrefix);
            if (direct != null) return direct;
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                PublicationMetadata parsed = publicationFromJson(children.next(), methodPrefix);
                if (parsed != null) return parsed;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                PublicationMetadata parsed = publicationFromJson(child, methodPrefix);
                if (parsed != null) return parsed;
            }
        }
        return null;
    }

    private PublicationMetadata publicationFromMarkup(Document document) {
        for (String selector : PUBLISHED_META_SELECTORS) {
            Element element = document.selectFirst(selector);
            if (element == null) continue;
            String raw = firstNonBlank(element.attr("content"), element.attr("datetime"), element.text());
            PublicationMetadata parsed = parsePublication(raw, "HTML_META");
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static PublicationMetadata parsePublication(String raw, String method) {
        if (raw == null || raw.isBlank() || !hasExplicitTimezone(raw)) return null;
        String value = raw.trim();
        // ISO-8601 permits a basic numeric offset (for example +0000) as well
        // as the extended +00:00 form. A number of established publishers use
        // the former in otherwise valid JSON-LD/article metadata, while the
        // JDK ISO parsers only accept the extended form here. Normalize only
        // that terminal offset and retain the publisher's original value in
        // publishedAtRaw for auditability.
        String parseableValue = normalizeBasicOffset(value);
        List<java.util.function.Function<String, Instant>> parsers = new ArrayList<>();
        parsers.add(Instant::parse);
        parsers.add(input -> OffsetDateTime.parse(input).toInstant());
        parsers.add(input -> ZonedDateTime.parse(input).toInstant());
        parsers.add(input -> ZonedDateTime.parse(input,
                DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        for (java.util.function.Function<String, Instant> parser : parsers) {
            try {
                Instant instant = parser.apply(parseableValue);
                return new PublicationMetadata(
                        java.time.LocalDateTime.ofInstant(instant, ZoneOffset.UTC), value, method);
            } catch (Exception ignored) {
                // Try the next timezone-preserving parser.
            }
        }
        return null;
    }

    private static String normalizeBasicOffset(String value) {
        if (value != null && value.matches(".*[+-]\\d{4}$")) {
            return value.substring(0, value.length() - 2) + ":" + value.substring(value.length() - 2);
        }
        return value;
    }

    private static boolean hasExplicitTimezone(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.endsWith("Z") || value.endsWith("z")
                || value.matches(".*[+-]\\d{2}:?\\d{2}$")
                || value.matches("(?i).*(GMT|UTC)([+-]\\d{1,2})?$");
    }

    private static String jsonText(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isValueNode()) return node.asText();
        StringBuilder out = new StringBuilder();
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            String value = jsonText(children.next());
            if (!value.isBlank()) out.append(value).append(' ');
        }
        return out.toString();
    }

    private static String findFirstText(JsonNode node, List<String> names) {
        if (node == null) return null;
        if (node.isObject()) {
            for (String name : names) {
                JsonNode value = node.get(name);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                String found = findFirstText(children.next(), names);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findFirstText(child, names);
                if (found != null) return found;
            }
        }
        return null;
    }

    static String normalizeText(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u00a0', ' ')
                // Typography-equivalent punctuation is canonicalized on both
                // capture and quote input. This keeps the binding exact while
                // avoiding retries caused only by smart quotes copied through
                // a different editor or JSON client.
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201A', '\'').replace('\u201B', '\'')
                .replace('\u02BC', '\'').replace('\uFF07', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u201E', '"').replace('\u201F', '"')
                .replace('\u2010', '-').replace('\u2011', '-')
                .replace('\u2012', '-').replace('\u2013', '-')
                .replace('\u2014', '-').replace('\u2212', '-')
                .replace("\u200B", "").replace("\u200C", "")
                .replace("\u200D", "").replace("\uFEFF", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean looksLikeJson(String body) {
        String value = body == null ? "" : body.stripLeading();
        return value.startsWith("{") || value.startsWith("[");
    }

    private static boolean looksLikeHtml(String body) {
        String value = body == null ? "" : body.stripLeading().toLowerCase(Locale.ROOT);
        return value.startsWith("<!doctype") || value.startsWith("<html")
                || value.startsWith("<head") || value.startsWith("<body")
                || value.contains("</p>") || value.contains("</article>");
    }

    private static AiNewsContentExtractionResult legacyFallback(String html, String sourceUrl) {
        Document document = Jsoup.parse(html == null ? "" : html,
                sourceUrl == null ? "" : sourceUrl);
        document.select("script,style,noscript,template,svg,canvas").remove();
        return new AiNewsContentExtractionResult(document.text(), null,
                "jsoup_document_text", "1", sha256(
                "remove:script,style,noscript,template,svg,canvas"), true,
                "primary_not_wired");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String attribute(Element element, String name) {
        return element == null ? null : element.attr(name);
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return "";
    }

    private static String trim(String value, int max) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) return "来源页面";
        return normalized.length() <= max ? normalized : normalized.substring(0, max).trim();
    }

    public record ParsedDocument(String title,
                                 String text,
                                 java.time.LocalDateTime publishedAtUtc,
                                 String publishedAtRaw,
                                 String publishedAtMethod,
                                 String extractorName,
                                 String extractorVersion,
                                 String extractorConfigHash,
                                 boolean extractionFallback,
                                 String extractionWarning) {
    }

    private record PublicationMetadata(java.time.LocalDateTime publishedAtUtc,
                                       String raw,
                                       String method) {
    }
}

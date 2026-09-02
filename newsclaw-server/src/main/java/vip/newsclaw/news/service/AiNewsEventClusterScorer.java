package vip.newsclaw.news.service;

import org.springframework.stereotype.Component;
import vip.newsclaw.common.text.Shingles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Explainable two-stage link scorer for an ever-growing online news stream.
 *
 * <p>Stage one is bounded blocking on time plus URL/title/entity/action overlap.
 * Stage two combines lexical and structured scores. A low-confidence pair is
 * never merged automatically: the caller creates a separate singleton and a
 * durable review proposal. The scorer is deliberately replaceable once P0-E
 * provides enough time-split labels for an embedding or cross-encoder model.</p>
 */
@Component
public class AiNewsEventClusterScorer {

    public static final String ALGORITHM_NAME = "deterministic_online_link";
    public static final String ALGORITHM_VERSION = "1";
    public static final String FEATURE_VERSION = "url_title_entity_action_time_v1";

    private static final Set<String> TITLE_STOPWORDS = Set.of(
            "ai", "artificial", "intelligence", "new", "news", "the", "a", "an", "and",
            "announces", "announced", "launches", "launched", "releases", "released",
            "update", "updated", "model", "models");
    private static final Set<String> GENERIC_ENTITIES = Set.of(
            "ai", "artificial intelligence", "人工智能", "model", "models", "模型",
            "company", "product", "research");
    private static final Map<String, List<String>> ACTION_TERMS = actionTerms();

    private final AiNewsEventClusteringProperties properties;

    public AiNewsEventClusterScorer(AiNewsEventClusteringProperties properties) {
        this.properties = properties;
    }

    public Score score(EventDocument incoming, EventDocument representative) {
        Set<String> incomingUrls = canonicalUrls(incoming.urls());
        Set<String> representativeUrls = canonicalUrls(representative.urls());
        boolean exactUrl = intersects(incomingUrls, representativeUrls);
        boolean exactKey = nonBlank(incoming.eventKey())
                && incoming.eventKey().equals(representative.eventKey());

        Set<String> incomingTitle = titleShingles(incoming.title());
        Set<String> representativeTitle = titleShingles(representative.title());
        double title = Shingles.jaccard(incomingTitle, representativeTitle);
        double narrative = Shingles.jaccard(
                titleShingles(join(incoming.title(), incoming.summary())),
                titleShingles(join(representative.title(), representative.summary())));
        double entities = Shingles.jaccard(normalizedEntities(incoming.entities()),
                normalizedEntities(representative.entities()));
        double actions = Shingles.jaccard(actionGroups(incoming), actionGroups(representative));
        double category = sameToken(incoming.category(), representative.category()) ? 1.0D : 0.0D;

        long hours = distanceHours(incoming.effectiveTime(), representative.effectiveTime());
        int maximumHours = effectiveMaxAgeHours();
        boolean withinTime = hours < 0 || hours <= maximumHours;
        double time = hours < 0 ? 0.25D
                : Math.max(0.0D, 1.0D - (double) hours / maximumHours);

        boolean blockedIn = exactKey || withinTime && (exactUrl
                || title >= effectiveMinBlock() || entities > 0.0D
                        || actions > 0.0D && narrative >= 0.08D);
        double lexical = 0.65D * title + 0.15D * narrative + 0.10D * entities
                + 0.05D * actions + 0.05D * time;
        double structured = 0.45D * entities + 0.25D * actions + 0.15D * time
                + 0.15D * category;
        double combined = exactKey || exactUrl && withinTime ? 1.0D
                : blockedIn ? Math.max(lexical, structured) : 0.0D;
        combined = rounded(combined);

        boolean strongStructured = entities >= 0.50D && actions >= 0.50D
                && category > 0.0D;
        boolean automatic = exactKey || withinTime && (exactUrl
                || combined >= effectiveAutoThreshold()
                && (title >= effectiveMinAutoTitle() || strongStructured));
        boolean review = !automatic && blockedIn && combined >= effectiveReviewThreshold();

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("exactUrl", exactUrl);
        breakdown.put("exactEventKey", exactKey);
        breakdown.put("titleJaccard", rounded(title));
        breakdown.put("narrativeJaccard", rounded(narrative));
        breakdown.put("entityJaccard", rounded(entities));
        breakdown.put("actionJaccard", rounded(actions));
        breakdown.put("categoryMatch", category);
        breakdown.put("timeDistanceHours", hours);
        breakdown.put("timeScore", rounded(time));
        breakdown.put("blockedIn", blockedIn);
        breakdown.put("lexicalScore", rounded(lexical));
        breakdown.put("structuredScore", rounded(structured));
        breakdown.put("score", combined);
        return new Score(combined, automatic, review,
                exactUrl ? "EXACT_URL" : exactKey ? "EXACT_EVENT_KEY" : "AUTO_RULES",
                Map.copyOf(breakdown));
    }

    public String configHash() {
        String material = String.join("|",
                ALGORITHM_NAME, ALGORITHM_VERSION, FEATURE_VERSION,
                "maxCandidates=" + effectiveMaxCandidates(),
                "maxAgeHours=" + effectiveMaxAgeHours(),
                "minBlock=" + effectiveMinBlock(),
                "auto=" + effectiveAutoThreshold(),
                "review=" + effectiveReviewThreshold(),
                "minAutoTitle=" + effectiveMinAutoTitle(),
                "lexical=.65,.15,.10,.05,.05",
                "structured=.45,.25,.15,.15",
                "titleStopwords=" + sortedMaterial(TITLE_STOPWORDS),
                "genericEntities=" + sortedMaterial(GENERIC_ENTITIES),
                "actionTerms=" + actionTermsMaterial());
        return sha256(material);
    }

    public int effectiveMaxCandidates() {
        return Math.max(10, Math.min(properties.getMaxCandidates(), 2_000));
    }

    public int effectiveMaxAgeHours() {
        return Math.max(1, Math.min(properties.getMaxCandidateAgeHours(), 24 * 31));
    }

    public double effectiveAutoThreshold() {
        return bounded(properties.getAutoLinkThreshold(), 0.50D, 0.99D);
    }

    public double effectiveReviewThreshold() {
        return Math.min(effectiveAutoThreshold(),
                bounded(properties.getReviewThreshold(), 0.20D, 0.95D));
    }

    private double effectiveMinBlock() {
        return bounded(properties.getMinTitleBlockingSimilarity(), 0.0D, 0.90D);
    }

    private double effectiveMinAutoTitle() {
        return bounded(properties.getMinAutoTitleSimilarity(), 0.0D, 0.95D);
    }

    private static Set<String> titleShingles(String value) {
        Set<String> result = new LinkedHashSet<>(Shingles.of(normalize(value)));
        result.removeAll(TITLE_STOPWORDS);
        return result;
    }

    private static Set<String> normalizedEntities(Collection<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String raw : values) {
            String value = normalize(raw);
            if (!value.isBlank() && !GENERIC_ENTITIES.contains(value)) result.add(value);
        }
        return result;
    }

    private static Set<String> actionGroups(EventDocument document) {
        String value = " " + normalize(join(document.title(), document.summary())) + " ";
        Set<String> groups = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : ACTION_TERMS.entrySet()) {
            if (entry.getValue().stream().anyMatch(term -> containsTerm(value, term))) {
                groups.add(entry.getKey());
            }
        }
        return groups;
    }

    private static boolean containsTerm(String haystack, String rawTerm) {
        String term = normalize(rawTerm);
        if (term.codePoints().anyMatch(AiNewsEventClusterScorer::isHan)) {
            return haystack.contains(term);
        }
        return haystack.contains(" " + term + " ");
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static Set<String> canonicalUrls(Collection<String> urls) {
        Set<String> result = new LinkedHashSet<>();
        if (urls == null) return result;
        for (String raw : urls) {
            String value = AiNewsEventService.canonicalUrl(raw);
            if (!value.isBlank()) result.add(value);
        }
        return result;
    }

    private static long distanceHours(LocalDateTime left, LocalDateTime right) {
        if (left == null || right == null) return -1L;
        return Math.abs(Duration.between(left, right).toHours());
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        return smaller.stream().anyMatch(larger::contains);
    }

    private static boolean sameToken(String left, String right) {
        return nonBlank(left) && normalize(left).equals(normalize(right));
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String join(String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) if (nonBlank(value)) present.add(value);
        return String.join(" ", present);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static double bounded(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double rounded(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String sortedMaterial(Collection<String> values) {
        return values.stream().map(AiNewsEventClusterScorer::normalize).sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String actionTermsMaterial() {
        Map<String, List<String>> ordered = new TreeMap<>(ACTION_TERMS);
        return ordered.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + sortedMaterial(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static Map<String, List<String>> actionTerms() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("launch", List.of("launch", "launches", "launched", "release", "releases",
                "released", "announce", "announces", "announced", "unveil", "unveils",
                "unveiled", "发布", "推出", "上线", "亮相"));
        values.put("funding", List.of("funding", "raised", "raises", "series", "investment",
                "融资", "募资", "投资"));
        values.put("acquisition", List.of("acquire", "acquired", "acquisition", "merger",
                "收购", "并购", "合并"));
        values.put("partnership", List.of("partner", "partnership", "collaboration",
                "合作", "伙伴", "联合"));
        values.put("open_source", List.of("open source", "open sourced", "apache license",
                "开源", "开放权重"));
        values.put("security", List.of("security", "vulnerability", "incident", "safety",
                "安全", "漏洞", "事故"));
        values.put("policy", List.of("regulation", "law", "policy", "executive order",
                "监管", "法规", "政策", "法案"));
        return Map.copyOf(values);
    }

    public record EventDocument(Long eventId,
                                String eventKey,
                                String title,
                                String summary,
                                String category,
                                Set<String> entities,
                                Set<String> urls,
                                LocalDateTime sourcePublishedAt,
                                LocalDateTime discoveredAt) {
        public EventDocument {
            entities = entities == null ? Set.of() : Set.copyOf(entities);
            urls = urls == null ? Set.of() : Set.copyOf(urls);
        }

        LocalDateTime effectiveTime() {
            return sourcePublishedAt == null ? discoveredAt : sourcePublishedAt;
        }
    }

    public record Score(double value,
                        boolean automaticLink,
                        boolean reviewSuggested,
                        String assignmentOrigin,
                        Map<String, Object> breakdown) {
    }
}

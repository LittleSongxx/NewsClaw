package vip.newsclaw.news.service;

import vip.newsclaw.news.service.AiNewsDiscoverySearchService.DiscoveryCandidate;
import vip.newsclaw.news.source.NewsSourceHashing;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-precision pre-capture story folding.
 *
 * <p>This is deliberately narrower than the durable event clusterer: it only
 * folds candidates when bilingual entity/product/action signatures agree. Two
 * editorially independent publishers may remain so downstream verification can
 * corroborate the event without letting one widely syndicated story consume
 * the capture queue.</p>
 */
final class AiNewsDiscoveryStoryDeduplicator {

    private static final Map<String, List<String>> ENTITY_ALIASES = entityAliases();
    private static final Map<String, List<String>> PRODUCT_ALIASES = productAliases();
    private static final Map<String, List<String>> ACTION_ALIASES = actionAliases();
    private static final Set<String> DISTINCTIVE_STOPWORDS = Set.of(
            "about", "after", "against", "agent", "agents", "artificial", "business",
            "company", "companies", "enterprise", "exclusive", "foundation", "future",
            "global", "intelligence", "latest", "machine", "major", "model", "models",
            "news", "open", "platform", "product", "report", "robot", "robots",
            "robotics", "source", "startup", "strategy", "technology", "update",
            "nvidia", "openai", "anthropic", "google", "microsoft", "amazon",
            "hugging", "face", "reuters", "fortune", "techcrunch", "bloomberg");
    private static final Pattern CAPITALIZED_TOKEN = Pattern.compile(
            "(?u)\\b[A-Z][A-Za-z0-9.+-]{3,}\\b");
    private static final Pattern ENGLISH_MONEY = Pattern.compile(
            "(?iu)(?:US\\$|USD\\s*|\\$)?(\\d+(?:\\.\\d+)?)\\s*(billion|million|bn|m)\\b");
    private static final Pattern CHINESE_USD_MONEY = Pattern.compile(
            "(?u)(\\d+(?:\\.\\d+)?)\\s*(亿|万)\\s*美元");

    private AiNewsDiscoveryStoryDeduplicator() {
    }

    static DeduplicationResult deduplicate(List<DiscoveryCandidate> ranked,
                                           AiNewsSourceRegistry sourceRegistry,
                                           int requestedMaxSourcesPerStory) {
        if (ranked == null || ranked.isEmpty()) {
            return new DeduplicationResult(List.of(), 0, 0, 0);
        }
        int maxSourcesPerStory = Math.max(1, Math.min(requestedMaxSourcesPerStory, 5));
        List<StoryCluster> clusters = new ArrayList<>();
        List<DiscoveryCandidate> kept = new ArrayList<>();
        int samePublisherDuplicates = 0;
        int sourceQuotaDuplicates = 0;

        for (DiscoveryCandidate candidate : ranked) {
            StoryFingerprint fingerprint = fingerprint(candidate, sourceRegistry);
            StoryCluster matching = clusters.stream()
                    .filter(cluster -> cluster.matches(fingerprint)).findFirst().orElse(null);
            String publisher = publisherIdentity(candidate, sourceRegistry);
            if (matching == null) {
                StoryCluster created = new StoryCluster();
                created.add(fingerprint, publisher);
                clusters.add(created);
                kept.add(candidate);
                continue;
            }
            if (matching.publishers.contains(publisher)) {
                samePublisherDuplicates++;
                continue;
            }
            if (matching.publishers.size() >= maxSourcesPerStory) {
                sourceQuotaDuplicates++;
                continue;
            }
            matching.add(fingerprint, publisher);
            kept.add(candidate);
        }
        return new DeduplicationResult(List.copyOf(kept), clusters.size(),
                samePublisherDuplicates, sourceQuotaDuplicates);
    }

    static boolean sameStory(DiscoveryCandidate left,
                             DiscoveryCandidate right,
                             AiNewsSourceRegistry sourceRegistry) {
        return sameStory(fingerprint(left, sourceRegistry), fingerprint(right, sourceRegistry));
    }

    /** Stable cross-run identity for scorecards and duplicate review grouping. */
    static long stableStoryId(DiscoveryCandidate candidate,
                              AiNewsSourceRegistry sourceRegistry) {
        StoryFingerprint value = fingerprint(candidate, sourceRegistry);
        String key;
        List<String> actions = value.actions.stream().sorted().toList();
        List<String> entities = value.entities.stream().sorted().toList();
        List<String> products = value.products.stream().sorted().toList();
        List<Long> amounts = value.amountsInMillions.stream().sorted().toList();
        if (!actions.isEmpty() && entities.size() >= 2) {
            key = actions.getFirst() + "|entities|" + String.join("|", entities.subList(0, 2));
        } else if (!actions.isEmpty() && !entities.isEmpty() && !products.isEmpty()) {
            key = actions.getFirst() + "|product|" + entities.getFirst() + "|" + products.getFirst();
        } else if (!actions.isEmpty() && !entities.isEmpty() && !amounts.isEmpty()) {
            key = actions.getFirst() + "|amount|" + entities.getFirst() + "|" + amounts.getFirst();
        } else {
            key = "url|" + AiNewsDiscoverySearchService.discoveryUrlAliasKey(
                    AiNewsDiscoverySearchService.canonicalDiscoveryUrl(candidate.url()));
        }
        long id = Long.parseUnsignedLong(NewsSourceHashing.sha256(key).substring(0, 16), 16)
                & Long.MAX_VALUE;
        return id == 0 ? 1 : id;
    }

    private static boolean sameStory(StoryFingerprint left, StoryFingerprint right) {
        Set<String> sharedActions = intersection(left.actions, right.actions);
        if (sharedActions.isEmpty()) return false;
        Set<String> sharedEntities = intersection(left.entities, right.entities);
        Set<String> sharedProducts = intersection(left.products, right.products);
        Set<Long> sharedAmounts = intersection(left.amountsInMillions, right.amountsInMillions);
        if (sharedEntities.size() >= 2) return true;
        if (!sharedEntities.isEmpty() && !sharedProducts.isEmpty()) return true;
        return !sharedEntities.isEmpty() && !sharedAmounts.isEmpty();
    }

    private static StoryFingerprint fingerprint(DiscoveryCandidate candidate,
                                                AiNewsSourceRegistry sourceRegistry) {
        String text = String.join(" ", value(candidate.title()), value(candidate.snippet()));
        String normalized = normalize(text);
        Set<String> entities = aliases(normalized, ENTITY_ALIASES);
        sourceRegistry.officialSourceKey(candidate.url())
                .map(AiNewsDiscoveryStoryDeduplicator::canonicalEntityKey)
                .ifPresent(entities::add);
        Set<String> products = aliases(normalized, PRODUCT_ALIASES);
        Matcher tokenMatcher = CAPITALIZED_TOKEN.matcher(text);
        while (tokenMatcher.find()) {
            String token = normalize(tokenMatcher.group());
            if (token.length() >= 4 && !DISTINCTIVE_STOPWORDS.contains(token)) products.add(token);
        }
        Set<String> actions = aliases(normalized, ACTION_ALIASES);
        Set<Long> amounts = moneyAmounts(text);
        return new StoryFingerprint(Set.copyOf(entities), Set.copyOf(products),
                Set.copyOf(actions), Set.copyOf(amounts));
    }

    private static Set<String> aliases(String normalizedText,
                                       Map<String, List<String>> vocabulary) {
        Set<String> matches = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : vocabulary.entrySet()) {
            if (entry.getValue().stream().map(AiNewsDiscoveryStoryDeduplicator::normalize)
                    .anyMatch(alias -> containsAlias(normalizedText, alias))) {
                matches.add(entry.getKey());
            }
        }
        return matches;
    }

    private static boolean containsAlias(String text, String alias) {
        if (alias.isBlank()) return false;
        if (alias.codePoints().anyMatch(AiNewsDiscoveryStoryDeduplicator::isHan)) {
            return text.contains(alias);
        }
        return (" " + text + " ").contains(" " + alias + " ");
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static Set<Long> moneyAmounts(String text) {
        Set<Long> amounts = new LinkedHashSet<>();
        Matcher english = ENGLISH_MONEY.matcher(value(text));
        while (english.find()) {
            double value = Double.parseDouble(english.group(1));
            String unit = english.group(2).toLowerCase(Locale.ROOT);
            amounts.add(Math.round(value * (unit.startsWith("b") ? 1_000D : 1D)));
        }
        Matcher chinese = CHINESE_USD_MONEY.matcher(value(text));
        while (chinese.find()) {
            double value = Double.parseDouble(chinese.group(1));
            amounts.add(Math.round(value * ("亿".equals(chinese.group(2)) ? 100D : 0.01D)));
        }
        return amounts;
    }

    private static String publisherIdentity(DiscoveryCandidate candidate,
                                            AiNewsSourceRegistry sourceRegistry) {
        return sourceRegistry.officialSourceKey(candidate.url())
                .map(key -> "official:" + key)
                .or(() -> sourceRegistry.trustedMediaSourceKey(candidate.url())
                        .map(key -> "media:" + key))
                .orElseGet(() -> "host:" + host(candidate.url()));
    }

    private static String canonicalEntityKey(String sourceKey) {
        if (sourceKey == null) return "";
        return switch (sourceKey) {
            case "google-deepmind" -> "google";
            case "alibaba-qwen" -> "alibaba-qwen";
            case "zhipu" -> "z-ai";
            default -> sourceKey;
        };
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isBlank()) return "unknown";
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}.+]+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static <T> Set<T> intersection(Set<T> left, Set<T> right) {
        Set<T> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static Map<String, List<String>> entityAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("openai", List.of("openai"));
        aliases.put("anthropic", List.of("anthropic"));
        aliases.put("google", List.of("google", "alphabet", "谷歌"));
        aliases.put("microsoft", List.of("microsoft", "微软"));
        aliases.put("nvidia", List.of("nvidia", "英伟达", "老黄"));
        aliases.put("huggingface", List.of("hugging face", "huggingface", "抱抱脸"));
        aliases.put("amazon-aws", List.of("amazon web services", "amazon", "aws", "亚马逊云"));
        aliases.put("meta", List.of("meta", "facebook", "脸书"));
        aliases.put("z-ai", List.of("z.ai", "z ai"));
        aliases.put("alibaba-qwen", List.of("alibaba", "qwen", "阿里巴巴", "通义千问"));
        aliases.put("deepseek", List.of("deepseek", "深度求索"));
        aliases.put("mistral", List.of("mistral"));
        aliases.put("salesforce", List.of("salesforce"));
        aliases.put("qualcomm", List.of("qualcomm", "高通"));
        aliases.put("amd", List.of("amd"));
        aliases.put("figure", List.of("figure ai", "figure"));
        return Map.copyOf(aliases);
    }

    private static Map<String, List<String>> productAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("google-ai-mode", List.of("ai mode"));
        aliases.put("microduck", List.of("microduck", "机器鸭"));
        aliases.put("quick-suite", List.of("quick suite"));
        aliases.put("geforce-now", List.of("geforce now"));
        aliases.put("project-groot", List.of("project groot", "gr00t"));
        aliases.put("claudeforce", List.of("claudeforce"));
        aliases.put("gemini-live", List.of("gemini live"));
        return Map.copyOf(aliases);
    }

    private static Map<String, List<String>> actionAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("acquisition", List.of("acquire", "acquires", "acquired", "acquisition",
                "buy", "buys", "purchase", "deal to buy", "收购", "买下", "拿下", "并购"));
        aliases.put("security", List.of("security", "cyber", "hack", "hacks", "hacked",
                "rogue ai", "defensive surge", "安全", "网络攻击", "黑客"));
        aliases.put("funding", List.of("funding", "raises", "raised", "series a", "series b",
                "seed round", "融资", "募资", "估值"));
        aliases.put("launch", List.of("launch", "launches", "launched", "release", "releases",
                "released", "unveil", "unveils", "unveiled", "introducing", "selling",
                "发布", "推出", "上线", "新品"));
        aliases.put("product-update", List.of("can now", "new ways", "new feature", "update",
                "updates", "updated", "新增", "更新", "升级"));
        aliases.put("partnership", List.of("partnering", "partnership", "collaboration",
                "合作", "联合"));
        aliases.put("expansion", List.of("expanding", "expansion", "opens office", "扩张", "拓展"));
        aliases.put("deployment", List.of("deploys", "deployed", "rolls out", "部署", "采用"));
        return Map.copyOf(aliases);
    }

    record DeduplicationResult(List<DiscoveryCandidate> candidates,
                               int provisionalStoryCount,
                               int samePublisherDuplicates,
                               int sourceQuotaDuplicates) {
    }

    private record StoryFingerprint(Set<String> entities,
                                    Set<String> products,
                                    Set<String> actions,
                                    Set<Long> amountsInMillions) {
    }

    private static final class StoryCluster {
        private final List<StoryFingerprint> fingerprints = new ArrayList<>();
        private final Set<String> publishers = new LinkedHashSet<>();

        boolean matches(StoryFingerprint candidate) {
            return fingerprints.stream().anyMatch(existing -> sameStory(existing, candidate));
        }

        void add(StoryFingerprint fingerprint, String publisher) {
            fingerprints.add(fingerprint);
            publishers.add(publisher);
        }
    }
}

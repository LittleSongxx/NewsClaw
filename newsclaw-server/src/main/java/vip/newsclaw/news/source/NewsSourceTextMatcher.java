package vip.newsclaw.news.source;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic broad-recall filter shared by structured source adapters. */
public final class NewsSourceTextMatcher {

    private static final Pattern QUERY_TERM = Pattern.compile("[\\p{L}\\p{N}]+",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "latest", "news", "release",
            "announcement", "artificial", "intelligence");

    private NewsSourceTextMatcher() {
    }

    public static boolean matches(NewsSourceResult result, NewsSourceQuery query) {
        if (query == null || query.query().isBlank()) return true;
        String haystack = (result.title() + " " + result.snippet()).toLowerCase(Locale.ROOT);
        var matcher = QUERY_TERM.matcher(query.query().toLowerCase(Locale.ROOT));
        Set<String> terms = new LinkedHashSet<>();
        while (matcher.find()) {
            String term = matcher.group();
            if (!QUERY_STOP_WORDS.contains(term) && (term.length() >= 3 || "ai".equals(term))) {
                terms.add(term);
            }
        }
        if (terms.isEmpty()) return true;
        return terms.stream().anyMatch(term -> containsTerm(haystack, term));
    }

    private static boolean containsTerm(String haystack, String term) {
        if (term.chars().anyMatch(ch -> ch > 127)) return haystack.contains(term);
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term)
                        + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE
                        | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS)
                .matcher(haystack).find();
    }
}

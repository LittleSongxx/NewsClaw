package vip.newsclaw.news.service;

import vip.newsclaw.exception.NewsClawException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects the common "fresh article about an old event" failure when the
 * evidence sentence explicitly dates the claimed action outside the frozen
 * window. This is intentionally a high-precision guard, not an attempt to
 * infer event time from every date on a page.
 */
final class AiNewsExplicitEventDateGuard {

    private static final Pattern ACTION = Pattern.compile(
            "(?i)\\b(launch(?:ed|es)?|releas(?:ed|es)?|announc(?:ed|es)?|"
                    + "introduc(?:ed|es)?|publish(?:ed|es)?|unveil(?:ed|s)?|"
                    + "rais(?:ed|es)?|secur(?:ed|es)?|complet(?:ed|es)?|"
                    + "sign(?:ed|s)?|partner(?:ed|s)?|acquir(?:ed|es)?)\\b|"
                    + "(推出|发布|宣布|完成|融资|签署|达成|收购)");
    private static final Pattern ISO_DATE = Pattern.compile(
            "(?<!\\d)(20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)");
    private static final Pattern CHINESE_DATE = Pattern.compile(
            "(?:(20\\d{2})年)?(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern MONTH_FIRST = Pattern.compile(
            "(?i)\\b(" + monthAlternation() + ")\\s+(\\d{1,2})(?:st|nd|rd|th)?"
                    + "(?:,?\\s+(20\\d{2}))?\\b");
    private static final Pattern DAY_FIRST = Pattern.compile(
            "(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(" + monthAlternation() + ")"
                    + "(?:\\s+(20\\d{2}))?\\b");
    private static final Map<String, Integer> MONTHS = months();

    private AiNewsExplicitEventDateGuard() {
    }

    static void validate(String quote, LocalDateTime sourcePublishedAt,
                         Instant windowStart, Instant windowEnd) {
        if (quote == null || quote.isBlank() || sourcePublishedAt == null
                || windowStart == null || windowEnd == null) return;
        LocalDate sourceDate = sourcePublishedAt.toLocalDate();
        for (String sentence : quote.split("(?<=[.!?。！？])\\s+|[\\n\\r]+")) {
            if (!ACTION.matcher(sentence).find()) continue;
            for (LocalDate date : dates(sentence, sourceDate)) {
                // Calendar precision cannot decide whether an action on the
                // boundary day happened before/after an intra-day timestamp.
                // Reject exactly when the UTC calendar-day interval has no
                // overlap with the half-open source window.
                Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                if (!dayStart.isBefore(windowEnd) || !windowStart.isBefore(dayEnd)) {
                    throw new NewsClawException(409,
                            "证据句明确把事件动作日期标为 " + date
                                    + "，与冻结窗口不相交；网页发布时间不能替代事件首次发生时间");
                }
            }
        }
    }

    private static List<LocalDate> dates(String sentence, LocalDate sourceDate) {
        List<LocalDate> out = new ArrayList<>();
        Matcher iso = ISO_DATE.matcher(sentence);
        while (iso.find()) add(out, integer(iso.group(1)), integer(iso.group(2)), integer(iso.group(3)));
        Matcher zh = CHINESE_DATE.matcher(sentence);
        while (zh.find()) addInferred(out, zh.group(1), zh.group(2), zh.group(3), sourceDate);
        Matcher monthFirst = MONTH_FIRST.matcher(sentence);
        while (monthFirst.find()) addInferred(out, monthFirst.group(3),
                String.valueOf(month(monthFirst.group(1))), monthFirst.group(2), sourceDate);
        Matcher dayFirst = DAY_FIRST.matcher(sentence);
        while (dayFirst.find()) addInferred(out, dayFirst.group(3),
                String.valueOf(month(dayFirst.group(2))), dayFirst.group(1), sourceDate);
        return out.stream().distinct().toList();
    }

    private static void addInferred(List<LocalDate> out, String yearRaw, String monthRaw,
                                    String dayRaw, LocalDate sourceDate) {
        int year = yearRaw == null ? sourceDate.getYear() : integer(yearRaw);
        int month = integer(monthRaw);
        int day = integer(dayRaw);
        try {
            LocalDate date = LocalDate.of(year, month, day);
            if (yearRaw == null && date.isAfter(sourceDate.plusDays(31))) date = date.minusYears(1);
            out.add(date);
        } catch (Exception ignored) {
            // Invalid prose dates have no deterministic admission meaning.
        }
    }

    private static void add(List<LocalDate> out, int year, int month, int day) {
        try {
            out.add(LocalDate.of(year, month, day));
        } catch (Exception ignored) {
            // Ignore invalid calendar strings.
        }
    }

    private static int month(String raw) {
        return MONTHS.getOrDefault(raw.toLowerCase(Locale.ROOT).replace(".", ""), 0);
    }

    private static int integer(String raw) {
        return Integer.parseInt(raw);
    }

    private static String monthAlternation() {
        return "January|Jan\\.?|February|Feb\\.?|March|Mar\\.?|April|Apr\\.?|May|"
                + "June|Jun\\.?|July|Jul\\.?|August|Aug\\.?|September|Sep\\.?|Sept\\.?|"
                + "October|Oct\\.?|November|Nov\\.?|December|Dec\\.?";
    }

    private static Map<String, Integer> months() {
        return Map.ofEntries(
                Map.entry("january", 1), Map.entry("jan", 1),
                Map.entry("february", 2), Map.entry("feb", 2),
                Map.entry("march", 3), Map.entry("mar", 3),
                Map.entry("april", 4), Map.entry("apr", 4),
                Map.entry("may", 5), Map.entry("june", 6), Map.entry("jun", 6),
                Map.entry("july", 7), Map.entry("jul", 7),
                Map.entry("august", 8), Map.entry("aug", 8),
                Map.entry("september", 9), Map.entry("sep", 9), Map.entry("sept", 9),
                Map.entry("october", 10), Map.entry("oct", 10),
                Map.entry("november", 11), Map.entry("nov", 11),
                Map.entry("december", 12), Map.entry("dec", 12));
    }
}

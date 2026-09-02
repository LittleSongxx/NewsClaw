package vip.newsclaw.news.source;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Strict source timestamp parsing: calendar dates without a timezone are hints, not instants. */
public final class NewsSourceTimeParser {

    private NewsSourceTimeParser() {
    }

    public static Instant parseExact(String value) {
        if (value == null || value.isBlank()) return null;
        String input = value.trim();
        if (!hasExplicitTimezone(input)) return null;
        if (input.matches(".*[+-]\\d{4}$")) {
            input = input.substring(0, input.length() - 2) + ":" + input.substring(input.length() - 2);
        }
        for (java.util.function.Function<String, Instant> parser
                : java.util.List.<java.util.function.Function<String, Instant>>of(
                Instant::parse,
                text -> OffsetDateTime.parse(text).toInstant(),
                text -> ZonedDateTime.parse(text).toInstant(),
                text -> ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())) {
            try {
                return parser.apply(input);
            } catch (DateTimeParseException ignored) {
                // Try the next timezone-preserving representation.
            }
        }
        return null;
    }

    public static boolean dateOnly(String value) {
        return value != null && value.trim().matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private static boolean hasExplicitTimezone(String value) {
        return value.endsWith("Z") || value.endsWith("z")
                || value.matches(".*[+-]\\d{2}:?\\d{2}$")
                || value.matches("(?i).*(GMT|UTC)([+-]\\d{1,2})?$")
                // RFC 822/1123 feeds also use named US zones.
                || value.matches("(?i).*(EST|EDT|CST|CDT|MST|MDT|PST|PDT)$");
    }
}

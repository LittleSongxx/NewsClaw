package vip.newsclaw.tool.search;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 搜索查询参数封装
 *
 * @param query     搜索关键词（必须）
 * @param freshness 时间范围过滤：day / week / month / year（可选）
 * @param language  语言偏好：zh-CN / en / auto（可选）
 * @param count     最大结果数：1-20，默认 5（可选）
 * @param topic     检索主题：general / news（可选）
 * @param startDate 绝对日期过滤起点（可选）
 * @param endDate   绝对日期过滤终点（可选）
 * @param includeDomains 只检索这些域名（可选）
 * @param excludeDomains 排除这些域名（可选）
 *
 * @author NewsClaw Team
 */
public record SearchQuery(
        String query,
        String freshness,
        String language,
        Integer count,
        String topic,
        LocalDate startDate,
        LocalDate endDate,
        List<String> includeDomains,
        List<String> excludeDomains
) {
    private static final int DEFAULT_COUNT = 5;
    private static final int MAX_COUNT = 20;

    public SearchQuery {
        query = query == null ? "" : query.trim();
        freshness = normalizeNullable(freshness);
        language = normalizeNullable(language);
        topic = normalizeTopic(topic);
        includeDomains = normalizeDomains(includeDomains, 300);
        excludeDomains = normalizeDomains(excludeDomains, 150);
        if (startDate != null && endDate != null && !startDate.isBefore(endDate)) {
            throw new IllegalArgumentException("startDate must be before endDate");
        }
    }

    /** Four-field constructor retained for every existing provider and plugin bridge. */
    public SearchQuery(String query, String freshness, String language, Integer count) {
        this(query, freshness, language, count, null, null, null, List.of(), List.of());
    }

    /** 从裸 query 字符串构建（向后兼容） */
    public static SearchQuery of(String query) {
        return new SearchQuery(query, null, null, null);
    }

    /** 获取 count，带默认值和上界限制 */
    public int resolvedCount() {
        if (count == null || count <= 0) return DEFAULT_COUNT;
        return Math.min(count, MAX_COUNT);
    }

    /** freshness 是否有效 */
    public boolean hasFreshness() {
        return freshness != null && !freshness.isBlank();
    }

    /** language 是否有效 */
    public boolean hasLanguage() {
        return language != null && !language.isBlank() && !"auto".equalsIgnoreCase(language);
    }

    public boolean hasAbsoluteDateRange() {
        return startDate != null || endDate != null;
    }

    public boolean hasIncludeDomains() {
        return includeDomains != null && !includeDomains.isEmpty();
    }

    public boolean hasExcludeDomains() {
        return excludeDomains != null && !excludeDomains.isEmpty();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeTopic(String value) {
        String normalized = normalizeNullable(value);
        if (normalized == null) return null;
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!List.of("general", "news").contains(normalized)) {
            throw new IllegalArgumentException("topic must be general or news");
        }
        return normalized;
    }

    private static List<String> normalizeDomains(List<String> values, int max) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) continue;
            String value = raw.trim().toLowerCase(Locale.ROOT)
                    .replaceFirst("^https?://", "")
                    .replaceFirst("^www\\.", "")
                    .replaceAll("/+$", "");
            if (!value.isBlank()) normalized.add(value);
            if (normalized.size() >= max) break;
        }
        return List.copyOf(normalized);
    }
}

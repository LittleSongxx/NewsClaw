package vip.newsclaw.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.newsclaw.tool.search.SearchQuery;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * 内置工具：网页搜索
 * <p>通过 WebSearchService 动态路由至最佳搜索 provider（含 keyless fallback），
 * 支持 freshness / language / count 等高级搜索参数。
 *
 * @author NewsClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool {

    private final WebSearchService webSearchService;

    // Tool name is pinned to "web_search" rather than the method-derived "search":
    // DashScope's native protocol reserves the function name "search" and rejects the
    // whole request with "InvalidParameter: Tool names are not allowed to be [search]",
    // which breaks tool use for every qwen/DashScope-native model that has this tool bound.
    @Tool(name = "web_search",
            description = "Search the internet for latest information. Use when querying real-time news, latest data, or uncertain facts. "
            + "Supports news/general topic, relative or absolute date filters, language, result count and domain allow/deny lists. "
            + "Search results and snippets are discovery hints only, never evidence.")
    public String search(
            @ToolParam(description = "Search keywords") String query,
            @ToolParam(description = "Time range filter: day (today), week (this week), month (this month), year (this year)", required = false) String freshness,
            @ToolParam(description = "Language preference: zh-CN (Chinese), en (English)", required = false) String language,
            @ToolParam(description = "Max results: 1-20, default 5", required = false) Integer count,
            @ToolParam(description = "Search topic: news for current news with published-date hints; general for official/product pages", required = false) String topic,
            @ToolParam(description = "Absolute publish-date lower bound, YYYY-MM-DD", required = false) String startDate,
            @ToolParam(description = "Absolute publish-date upper bound, YYYY-MM-DD", required = false) String endDate,
            @ToolParam(description = "Comma-separated domains to include; use for official-source search", required = false) String includeDomains,
            @ToolParam(description = "Comma-separated domains to exclude", required = false) String excludeDomains
    ) {
        SearchQuery searchQuery = new SearchQuery(query, freshness, language, count, topic,
                parseDate(startDate, "startDate"), parseDate(endDate, "endDate"),
                split(includeDomains), split(excludeDomains));
        return webSearchService.search(searchQuery);
    }

    private static LocalDate parseDate(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must use YYYY-MM-DD");
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }
}

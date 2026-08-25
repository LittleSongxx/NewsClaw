package vip.newsclaw.news.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.news.model.AiNewsEvidenceRequest;
import vip.newsclaw.news.model.AiNewsEventDetail;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEventUpsertRequest;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.service.OfficialSourceEvidenceCaptureService;
import vip.newsclaw.news.source.NewsSourceProviderRegistry;
import vip.newsclaw.tool.builtin.ToolExecutionContext;
import vip.newsclaw.workspace.conversation.ConversationService;
import vip.newsclaw.workspace.conversation.model.ConversationEntity;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Compact Agent tool for the event/evidence half of the AI news workflow. */
@Slf4j
@Component("aiNewsEventTool")
@RequiredArgsConstructor
public class AiNewsEventTool {

    private final AiNewsEventService eventService;
    private final OfficialSourceEvidenceCaptureService officialCaptureService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    /** Optional while older test/extension deployments migrate to the source SPI. */
    private NewsSourceProviderRegistry sourceProviderRegistry;

    @Tool(name = "ai_news_event", description = "管理 AI 行业动态事件及其来源证据。"
            + "action 可选 search_sources、fetch_source、source_health、upsert、list、get、capture_official、mark_verified、dismiss、link_run、link_content、link_wiki、mark_published、archive。"
            + "search_sources/fetch_source 只返回带 provenance 的候选资料，不会自动入库、核验或发布；读取后必须由 Agent 显式调用 upsert 写入 claim/quote。"
            + "upsert 必须提供 title 和 sourceUrl；官方来源优先，媒体来源需交叉核验。"
            + "未核验事件不得直接进入内容生产或对外交付。")
    public String ai_news_event(
            @ToolParam(description = "操作：search_sources/fetch_source/source_health/upsert/list/get/capture_official/mark_verified/dismiss/link_run/link_content/link_wiki/mark_published/archive") String action,
            @ToolParam(description = "事件 id，get/mark_verified/dismiss/link_run/link_content/archive 使用", required = false) String eventId,
            @ToolParam(description = "候选事件标题，upsert 使用", required = false) String title,
            @ToolParam(description = "事件摘要", required = false) String summary,
            @ToolParam(description = "分类：model/robotics/infrastructure/product/open_source/industry/policy", required = false) String category,
            @ToolParam(description = "来源 URL；upsert 时必须提供", required = false) String sourceUrl,
            @ToolParam(description = "来源标题", required = false) String sourceTitle,
            @ToolParam(description = "来源等级：official/media/community", required = false) String sourceTier,
            @ToolParam(description = "来源支持的事实声明", required = false) String claim,
            @ToolParam(description = "来源原文摘录", required = false) String quote,
            @ToolParam(description = "实体标签，逗号分隔", required = false) String entities,
            @ToolParam(description = "关联 id，link_* 使用；推荐统一传 linkedId", required = false) String linkedId,
            @ToolParam(description = "link_wiki 的语义化别名；与 linkedId 二选一", required = false) String wikiPageId,
            @ToolParam(description = "link_wiki 的兼容别名；与 wikiPageId/linkedId 二选一", required = false) String pageId,
            @ToolParam(description = "link_content 的语义化别名；与 linkedId 二选一", required = false) String contentId,
            @ToolParam(description = "link_run 的语义化别名；与 linkedId 二选一", required = false) String runId,
            @ToolParam(description = "link_content 的平台：gzh 或 xhs", required = false) String platform,
            @ToolParam(description = "list 的状态过滤", required = false) String status,
            @ToolParam(description = "search_sources 的检索词，必填", required = false) String query,
            @ToolParam(description = "search_sources 的来源 provider id，逗号分隔；为空时查询全部可用 provider", required = false) String providerIds,
            @ToolParam(description = "fetch_source 的 provider id", required = false) String providerId,
            @ToolParam(description = "search_sources 的每次返回条数，1-100，默认 10", required = false) String sourceLimit,
            @ToolParam(description = "search_sources 的语言，如 zh-CN 或 en", required = false) String language,
            @ToolParam(description = "search_sources 的起始时间，ISO-8601 UTC，如 2026-08-24T00:00:00Z", required = false) String since,
            @Nullable ToolContext ctx) {
        try {
            Long workspaceId = resolveWorkspace(ctx);
            String op = action == null ? "" : action.trim().toLowerCase();
            return switch (op) {
                case "search_sources" -> searchSources(query, providerIds, sourceLimit, language, since);
                case "fetch_source" -> fetchSource(providerId, sourceUrl);
                case "source_health" -> sourceHealth();
                case "upsert" -> json(eventService.upsert(workspaceId,
                        new AiNewsEventUpsertRequest(null, title, summary, category,
                                split(entities), LocalDateTime.now(), null, List.of(), List.of(),
                                List.of(new AiNewsEvidenceRequest(sourceUrl, sourceTitle, null,
                                        sourceTier, claim, quote, 0.5D)))));
                case "list" -> json(eventService.page(workspaceId, 1, 20, category, status, null).getRecords());
                case "get" -> json(eventService.get(workspaceId, parseId(eventId, "eventId")));
                case "capture_official" -> json(officialCaptureService.capture(workspaceId,
                        parseId(eventId, "eventId"), sourceUrl, claim));
                case "mark_verified" -> json(eventService.verify(workspaceId, parseId(eventId, "eventId"), null, null));
                case "dismiss" -> json(eventService.dismiss(workspaceId, parseId(eventId, "eventId")));
                case "mark_published" -> json(eventService.markPublished(workspaceId, parseId(eventId, "eventId")));
                case "archive" -> json(eventService.archive(workspaceId, parseId(eventId, "eventId")));
                case "link_run" -> json(eventService.linkRun(workspaceId, parseId(eventId, "eventId"),
                        parseId(firstNonBlank(linkedId, runId), "runId")));
                case "link_content" -> json(eventService.linkContent(workspaceId, parseId(eventId, "eventId"),
                        parseId(firstNonBlank(linkedId, contentId), "contentId"), platform));
                case "link_wiki" -> json(eventService.linkWiki(workspaceId, parseId(eventId, "eventId"),
                        parseId(firstNonBlank(linkedId, firstNonBlank(wikiPageId, pageId)), "wikiPageId")));
                default -> "Error: unknown action; use search_sources/fetch_source/source_health/upsert/list/get/capture_official/mark_verified/dismiss/link_run/link_content/link_wiki/mark_published/archive";
            };
        } catch (Exception e) {
            log.debug("ai_news_event failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @Autowired(required = false)
    void setSourceProviderRegistry(NewsSourceProviderRegistry sourceProviderRegistry) {
        this.sourceProviderRegistry = sourceProviderRegistry;
    }

    private String searchSources(String query, String providerIds, String sourceLimit,
                                 String language, String since) throws Exception {
        NewsSourceProviderRegistry registry = requireSourceProviderRegistry();
        String searchQuery = requiredText(query, "query");
        List<?> results = registry.search(new vip.newsclaw.news.source.NewsSourceQuery(searchQuery,
                        parseSourceLimit(sourceLimit), language, parseSince(since)),
                split(providerIds));
        return json(Map.of(
                "mode", "read_only_candidate_sources",
                "message", "Candidate source material only. Inspect it, then explicitly call upsert with claim and quote; this action never verifies or writes an event.",
                "results", results));
    }

    private String fetchSource(String providerId, String sourceUrl) throws Exception {
        NewsSourceProviderRegistry registry = requireSourceProviderRegistry();
        String id = requiredText(providerId, "providerId");
        String url = requiredText(sourceUrl, "sourceUrl");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sourceUrl must be an absolute http/https URI");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()
                || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("sourceUrl must be an absolute http/https URI");
        }
        Object result = registry.fetch(id, uri)
                .orElseThrow(() -> new IllegalArgumentException("source was unavailable or rejected by provider: " + id));
        return json(Map.of(
                "mode", "read_only_candidate_source",
                "message", "Fetched source material only. It is not evidence until an Agent explicitly records a supported claim and quote with upsert.",
                "result", result));
    }

    private String sourceHealth() throws Exception {
        NewsSourceProviderRegistry registry = requireSourceProviderRegistry();
        return json(registry.all().stream().map(vip.newsclaw.news.source.NewsSourceProvider::health).toList());
    }

    private NewsSourceProviderRegistry requireSourceProviderRegistry() {
        if (sourceProviderRegistry == null) {
            throw new IllegalStateException("news source provider registry is unavailable");
        }
        return sourceProviderRegistry;
    }

    private Long resolveWorkspace(@Nullable ToolContext ctx) {
        Long workspaceId = ChatOrigin.from(ctx).workspaceId();
        if (workspaceId != null) return workspaceId;
        String conversationId = ToolExecutionContext.conversationId(ctx);
        if (conversationId != null && !conversationId.isBlank()) {
            ConversationEntity conversation = conversationService.findByConversationId(conversationId);
            if (conversation != null && conversation.getWorkspaceId() != null) return conversation.getWorkspaceId();
        }
        return 1L;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static Long parseId(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a numeric id");
        }
    }

    private static String firstNonBlank(String primary, String alias) {
        return primary != null && !primary.isBlank() ? primary : alias;
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static int parseSourceLimit(String value) {
        if (value == null || value.isBlank()) return 10;
        try {
            int limit = Integer.parseInt(value.trim());
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("sourceLimit must be an integer between 1 and 100");
            }
            return limit;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sourceLimit must be an integer between 1 and 100");
        }
    }

    private static Instant parseSince(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("since must be an ISO-8601 UTC timestamp");
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}

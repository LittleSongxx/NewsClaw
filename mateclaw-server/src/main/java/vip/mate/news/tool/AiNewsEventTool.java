package vip.mate.news.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.news.model.AiNewsEvidenceRequest;
import vip.mate.news.model.AiNewsEventDetail;
import vip.mate.news.model.AiNewsEventEntity;
import vip.mate.news.model.AiNewsEventUpsertRequest;
import vip.mate.news.service.AiNewsEventService;
import vip.mate.news.service.OfficialSourceEvidenceCaptureService;
import vip.mate.tool.builtin.ToolExecutionContext;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/** Compact Agent tool for the event/evidence half of the AI news workflow. */
@Slf4j
@Component("aiNewsEventTool")
@RequiredArgsConstructor
public class AiNewsEventTool {

    private final AiNewsEventService eventService;
    private final OfficialSourceEvidenceCaptureService officialCaptureService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    @Tool(name = "ai_news_event", description = "管理 AI 行业动态事件及其来源证据。"
            + "action 可选 upsert、list、get、capture_official、mark_verified、dismiss、link_run、link_content、link_wiki、mark_published、archive。"
            + "upsert 必须提供 title 和 sourceUrl；官方来源优先，媒体来源需交叉核验。"
            + "未核验事件不得直接进入内容生产或对外交付。")
    public String ai_news_event(
            @ToolParam(description = "操作：upsert/list/get/capture_official/mark_verified/dismiss/link_run/link_content/link_wiki/mark_published/archive") String action,
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
            @Nullable ToolContext ctx) {
        try {
            Long workspaceId = resolveWorkspace(ctx);
            String op = action == null ? "" : action.trim().toLowerCase();
            return switch (op) {
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
            default -> "Error: unknown action; use upsert/list/get/capture_official/mark_verified/dismiss/link_run/link_content/link_wiki/mark_published/archive";
            };
        } catch (Exception e) {
            log.debug("ai_news_event failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private Long resolveWorkspace(@Nullable ToolContext ctx) {
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

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}

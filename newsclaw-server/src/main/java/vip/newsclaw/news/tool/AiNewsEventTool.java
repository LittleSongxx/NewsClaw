package vip.newsclaw.news.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
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
import vip.newsclaw.news.service.AiNewsAtomicFactGuard;
import vip.newsclaw.news.service.AiNewsDiscoverySearchService;
import vip.newsclaw.news.service.AiNewsSourceCaptureService;
import vip.newsclaw.news.service.OfficialSourceEvidenceCaptureService;
import vip.newsclaw.news.source.NewsSourceProviderRegistry;
import vip.newsclaw.tool.ConcurrencyUnsafe;
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
public class AiNewsEventTool {

    /** Leave margin below ToolResultProperties' default 8,000-char spill boundary. */
    private static final int MAX_INLINE_DISCOVERY_CHARS = 7_800;

    private final AiNewsEventService eventService;
    private final OfficialSourceEvidenceCaptureService officialCaptureService;
    private final AiNewsSourceCaptureService sourceCaptureService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    /** Optional while older test/extension deployments migrate to the source SPI. */
    private NewsSourceProviderRegistry sourceProviderRegistry;
    /** Optional while extension-only deployments migrate to vertical fused retrieval. */
    private AiNewsDiscoverySearchService discoverySearchService;

    /**
     * The compatibility constructor below means Spring can no longer infer
     * which constructor is the component constructor. Keep the complete
     * dependency set explicit and mark it as the one Spring should use.
     */
    @Autowired
    public AiNewsEventTool(AiNewsEventService eventService,
                           OfficialSourceEvidenceCaptureService officialCaptureService,
                           AiNewsSourceCaptureService sourceCaptureService,
                           ConversationService conversationService,
                           ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.officialCaptureService = officialCaptureService;
        this.sourceCaptureService = sourceCaptureService;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    /** Compatibility constructor for extensions compiled before capture-bound upsert. */
    public AiNewsEventTool(AiNewsEventService eventService,
                           OfficialSourceEvidenceCaptureService officialCaptureService,
                           ConversationService conversationService,
                           ObjectMapper objectMapper) {
        this(eventService, officialCaptureService, null, conversationService, objectMapper);
    }

    @ConcurrencyUnsafe("workspace-scoped capture and event mutations share ordered IDs and evidence state; execute every ai_news_event call in its own batch")
    @Tool(name = "ai_news_event", description = "管理 AI 行业动态事件及其来源证据。"
            + "action 可选 discover、source_plan、window_summary、search_sources、fetch_source、source_health、capture_source、read_capture、upsert、list、get、capture_official、mark_verified、dismiss、link_run、link_content、link_wiki、mark_published、archive。"
            + "source_health 会检查全部已配置结构化新闻来源，只需要 action；必须精确调用 {\"action\":\"source_health\"}，不需要也不要提供 source ID、URL、providerId 或事件 ID。"
            + "当用户把当前部署的来源健康检查明确列为回答前置步骤时，应先执行该只读 action；不能因为后续语义判断本身不依赖健康状态而跳过。"
            + "search_sources/fetch_source/web_search/browser_use 只产生发现线索，绝不能直接作为入库证据。"
            + "正式入库前必须先 capture_source(sourceUrl)，其 excerpt 已是可直接引用的精确正文；只有所需原文不在 excerpt 且 truncated=true 时，才按 nextOffset 调 read_capture。"
            + "每个 URL 必须串行完成 capture_source→必要的 read_capture→upsert 后再处理下一个 URL；禁止并行批量 capture 后手工汇总 ID。captureId 必须从成功响应逐字复制，不能推算或改写。"
            + "discover 必须同时提供 windowStart 和 windowEnd，使用 ISO-8601 UTC 半开来源窗口；缺少任一字段会在联网前拒绝。"
            + "upsert 必须提供 captureId、一个不超过 512 字符的原子 claim、逐字来自 capture 的 quote、semanticRelation、windowStart 和 windowEnd；卡片 title/summary/claims 全部由该 claim 派生，自由填写的标题摘要不会入库。"
            + "后端会精确定位 quote，并拒绝缺少可靠发布时间或发布时间不在 [windowStart,windowEnd) 内的来源。semanticRelation 只判断 quote 对 claim 是 entails/contradicts/partial/unrelated/hedged。"
            + "来源等级、交叉核验、冲突、引用许可和生命周期状态由服务端确定，Agent 不得自行覆盖。"
            + "未核验事件不得直接进入内容生产或对外交付。mark_verified、dismiss、archive、link_* 和 mark_published 仅接受可归因的人工上下文；定时任务和无上下文调用会被拒绝。")
    public String ai_news_event(
            @ToolParam(description = "操作：discover/source_plan/window_summary/search_sources/fetch_source/source_health/capture_source/read_capture/upsert/list/get/capture_official/mark_verified/dismiss/link_run/link_content/link_wiki/mark_published/archive。source_health 只传本字段，不需要其他参数") String action,
            @ToolParam(description = "事件 id，get/mark_verified/dismiss/link_run/link_content/archive 使用", required = false) String eventId,
            @ToolParam(description = "兼容字段；严格 upsert 会忽略并由原子 claim 生成卡片标题", required = false) String title,
            @ToolParam(description = "兼容字段；严格 upsert 会忽略并由原子 claim 生成摘要", required = false) String summary,
            @ToolParam(description = "分类：model/product/open_source/security/infrastructure/partnership/funding/robotics/industry/policy", required = false) String category,
            @ToolParam(description = "来源 URL；capture_source/capture_official/fetch_source 使用，严格 upsert 会忽略并从 capture 派生", required = false) String sourceUrl,
            @ToolParam(description = "兼容字段；严格 upsert 会忽略并从 capture 派生来源标题", required = false) String sourceTitle,
            @ToolParam(description = "兼容字段；严格 upsert 会忽略并由后端按 capture 最终 URL 计算来源等级", required = false) String sourceTier,
            @ToolParam(description = "来源支持的事实声明", required = false) String claim,
            @ToolParam(description = "来源原文摘录", required = false) String quote,
            @ToolParam(description = "quote 对 claim 的语义关系：entails/contradicts/partial/unrelated/hedged；upsert 必填", required = false) String semanticRelation,
            @ToolParam(description = "语义关系置信度，0-1", required = false) String relationConfidence,
            @ToolParam(description = "实体标签，逗号分隔", required = false) String entities,
            @ToolParam(description = "关联 id，link_* 使用；推荐统一传 linkedId", required = false) String linkedId,
            @ToolParam(description = "link_wiki 的语义化别名；与 linkedId 二选一", required = false) String wikiPageId,
            @ToolParam(description = "link_wiki 的兼容别名；与 wikiPageId/linkedId 二选一", required = false) String pageId,
            @ToolParam(description = "link_content 的语义化别名；与 linkedId 二选一", required = false) String contentId,
            @ToolParam(description = "link_run 的语义化别名；与 linkedId 二选一", required = false) String runId,
            @ToolParam(description = "link_content 的平台：gzh 或 xhs", required = false) String platform,
            @ToolParam(description = "list 的状态过滤", required = false) String status,
            @ToolParam(description = "discover/search_sources 的主题或检索词", required = false) String query,
            @ToolParam(description = "search_sources 的来源 provider id，逗号分隔；为空时查询全部可用 provider", required = false) String providerIds,
            @ToolParam(description = "fetch_source 的 provider id", required = false) String providerId,
            @ToolParam(description = "discover 的候选上限（最高 50）或 search_sources 的返回条数（最高 100）", required = false) String sourceLimit,
            @ToolParam(description = "search_sources 的语言，如 zh-CN 或 en", required = false) String language,
            @ToolParam(description = "search_sources 的起始时间，ISO-8601 UTC，如 2026-08-24T00:00:00Z", required = false) String since,
            @ToolParam(description = "capture_source 成功响应中的来源快照 ID；read_capture/upsert 必填，必须逐字复制，禁止推算或改写", required = false) String captureId,
            @ToolParam(description = "read_capture 的正文字符起点，使用上次返回的 nextOffset，默认 0", required = false) String startOffset,
            @ToolParam(description = "discover/upsert/window_summary 必填的来源时间窗起点，ISO-8601 UTC，闭区间", required = false) String windowStart,
            @ToolParam(description = "discover/upsert/window_summary 必填的来源时间窗终点，ISO-8601 UTC，开区间", required = false) String windowEnd,
            @Nullable ToolContext ctx) {
        try {
            String op = action == null ? "" : action.trim().toLowerCase();
            // Read-only provider operations do not need a tenant. Anything
            // that persists or mutates an event must resolve one explicitly;
            // falling back to workspace 1 can cross tenant boundaries.
            Long workspaceId = requiresWorkspace(op) ? resolveWorkspace(ctx) : null;
            if (requiresHumanOrigin(op)) requireHumanOrigin(ctx);
            return switch (op) {
                case "discover" -> discover(workspaceId, query, windowStart, windowEnd, sourceLimit);
                case "source_plan" -> json(requireDiscoveryService().sourcePlan(category));
                case "window_summary" -> json(eventService.summarizeWindow(workspaceId,
                        parseRequiredInstant(windowStart, "windowStart"),
                        parseRequiredInstant(windowEnd, "windowEnd")));
                case "search_sources" -> searchSources(query, providerIds, sourceLimit, language, since);
                case "fetch_source" -> fetchSource(providerId, sourceUrl);
                case "source_health" -> sourceHealth();
                case "capture_source" -> json(requireSourceCaptureService().capture(workspaceId, sourceUrl));
                case "read_capture" -> json(requireSourceCaptureService().read(workspaceId,
                        parseId(captureId, "captureId"), parseOffset(startOffset)));
                case "upsert" -> json(strictUpsert(workspaceId, category, entities, claim, quote,
                        sourceTier, semanticRelation, relationConfidence, captureId,
                        windowStart, windowEnd));
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
                default -> "Error: unknown action; use discover/source_plan/window_summary/search_sources/fetch_source/source_health/capture_source/read_capture/upsert/list/get/capture_official/mark_verified/dismiss/link_run/link_content/link_wiki/mark_published/archive";
            };
        } catch (Exception e) {
            log.debug("ai_news_event failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "ai_news_delivery_acknowledge", description = "记录人工审核后的内容工件 SHA-256；仅写入 operator_acknowledged，不会伪称平台已发布。")
    public String acknowledgeDelivery(
            @ToolParam(description = "AI 动态事件 id") String eventId,
            @ToolParam(description = "已审核内容工件的 SHA-256（64 位十六进制）") String artifactHash,
            @Nullable ToolContext ctx) {
        try {
            requireHumanOrigin(ctx);
            return json(eventService.acknowledgeDelivery(resolveWorkspace(ctx), parseId(eventId, "eventId"), artifactHash));
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Autowired(required = false)
    void setSourceProviderRegistry(NewsSourceProviderRegistry sourceProviderRegistry) {
        this.sourceProviderRegistry = sourceProviderRegistry;
    }

    @Autowired(required = false)
    void setDiscoverySearchService(AiNewsDiscoverySearchService discoverySearchService) {
        this.discoverySearchService = discoverySearchService;
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
                "message", "Candidate source material only. It is not evidence. Select a URL, call capture_source, read the returned snapshot, then upsert with captureId and an exact quote.",
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
                "message", "Fetched source material only. It is not evidence. Call capture_source for this URL and quote only from the server-owned snapshot before upsert.",
                "result", result));
    }

    private String sourceHealth() throws Exception {
        NewsSourceProviderRegistry registry = requireSourceProviderRegistry();
        return json(registry.all().stream().map(vip.newsclaw.news.source.NewsSourceProvider::health).toList());
    }

    private AiNewsEventEntity strictUpsert(Long workspaceId,
                                           String category,
                                           String entities,
                                           String claim,
                                           String quote,
                                           String sourceTier,
                                           String semanticRelation,
                                           String relationConfidence,
                                           String captureId,
                                           String windowStart,
                                           String windowEnd) {
        Instant start = parseRequiredInstant(windowStart, "windowStart");
        Instant end = parseRequiredInstant(windowEnd, "windowEnd");
        AiNewsAtomicFactGuard.AtomicFact fact;
        try {
            fact = AiNewsAtomicFactGuard.prepare(category, split(entities), claim, start);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        return eventService.upsertCaptured(workspaceId,
                new AiNewsEventUpsertRequest(fact.eventKeyMaterial(), fact.title(), fact.summary(),
                        fact.category(), fact.entities(), LocalDateTime.now(), null,
                        List.of(fact.summary()), List.of(),
                        List.of(new AiNewsEvidenceRequest(null, null, null,
                                sourceTier, fact.summary(), quote, 0.5D, semanticRelation,
                                parseConfidence(relationConfidence), parseId(captureId, "captureId")))),
                start, end);
    }

    private NewsSourceProviderRegistry requireSourceProviderRegistry() {
        if (sourceProviderRegistry == null) {
            throw new IllegalStateException("news source provider registry is unavailable");
        }
        return sourceProviderRegistry;
    }

    private AiNewsSourceCaptureService requireSourceCaptureService() {
        if (sourceCaptureService == null) {
            throw new IllegalStateException("news source capture service is unavailable in this deployment");
        }
        return sourceCaptureService;
    }

    private AiNewsDiscoverySearchService requireDiscoveryService() {
        if (discoverySearchService == null) {
            throw new IllegalStateException("AI news fused discovery service is unavailable");
        }
        return discoverySearchService;
    }

    /**
     * Keep the model-facing discovery result below the generic 8k tool-result
     * spill threshold. A spill pointer is useful when an Agent owns read_file,
     * but the deliberately narrow news-radar tool scope does not; returning the
     * full provider snippets and domain arrays therefore made the candidate set
     * effectively unreadable and caused compensating searches. Full ranking and
     * query metadata remains available inside the service result and logs, while
     * the Agent receives exactly what it needs to select URLs for capture.
     */
    private String discover(Long workspaceId, String query, String windowStart, String windowEnd,
                            String sourceLimit) throws Exception {
        AiNewsDiscoverySearchService.DiscoveryBatch batch = requireDiscoveryService().discover(workspaceId, query,
                parseRequiredInstant(windowStart, "windowStart"),
                parseRequiredInstant(windowEnd, "windowEnd"), parseOptionalLimit(sourceLimit));
        List<CompactDiscoveryCandidate> candidates = new java.util.ArrayList<>(batch.candidates().stream()
                .map(item -> new CompactDiscoveryCandidate(item.rank(), compactTitle(item.title()), item.url(),
                        item.officialDomain() ? "official"
                                : item.trustedMediaDomain() ? "media" : "other",
                        compactPublishedAtHint(item.publishedAtHint()),
                        item.temporalStatus().name(), item.selectionLane()))
                .toList());
        List<CompactQueryExecution> executions = batch.executions().stream()
                .map(item -> new CompactQueryExecution(item.family(), item.providerId(),
                        item.resultCount(), compactExecutionStatus(item.failureMessage())))
                .toList();
        int successfulQueryCount = (int) executions.stream()
                .filter(item -> "ok".equals(item.status())).count();
        int cachedQueryCount = (int) batch.executions().stream()
                .filter(AiNewsDiscoverySearchService.QueryExecution::fromCache).count();
        List<CompactQueryExecution> failedExecutions = executions.stream()
                .filter(item -> !"ok".equals(item.status())).toList();
        String serialized;
        do {
            CompactDiscoveryBatch response = new CompactDiscoveryBatch(batch.mode(),
                    batch.evidenceEligible(), batch.windowStart(), batch.windowEnd(),
                    batch.queryCount(), batch.uniqueUrlCount(), candidates.size(),
                    candidates.size() < batch.candidates().size(), List.copyOf(candidates),
                    successfulQueryCount, cachedQueryCount, failedExecutions,
                    batch.structuredSourceCount(), batch.observedAt(), batch.rankingPolicyVersion(),
                    batch.snapshotHash(), batch.rankingHash(), batch.diagnostics(),
                    batch.discoveryRunId(), batch.snapshotPersisted(), batch.message());
            serialized = json(response);
            if (serialized.length() <= MAX_INLINE_DISCOVERY_CHARS || candidates.isEmpty()) break;
            // Drop only the lowest fused-rank candidate. The total unique URL
            // count and truncation flag remain explicit for auditability.
            candidates.removeLast();
        } while (true);
        return serialized;
    }

    private static String compactExecutionStatus(String failureMessage) {
        if (failureMessage == null || failureMessage.isBlank()) return "ok";
        String normalized = failureMessage.replaceAll("\\s+", " ").trim();
        return "failed: " + (normalized.length() <= 120
                ? normalized : normalized.substring(0, 120).trim());
    }

    private static String compactTitle(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 140 ? normalized : normalized.substring(0, 140).trim();
    }

    private static String compactPublishedAtHint(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        for (java.util.function.Function<String, java.time.Instant> parser
                : List.<java.util.function.Function<String, java.time.Instant>>of(
                java.time.Instant::parse,
                input -> java.time.ZonedDateTime.parse(input,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),
                input -> java.time.OffsetDateTime.parse(input).toInstant())) {
            try {
                return parser.apply(normalized).toString();
            } catch (Exception ignored) {
                // Try the next timezone-preserving representation.
            }
        }
        try {
            return java.time.LocalDate.parse(normalized).toString();
        } catch (Exception ignored) {
            return normalized.length() <= 32 ? normalized : normalized.substring(0, 32).trim();
        }
    }

    private record CompactDiscoveryBatch(String mode,
                                         boolean evidenceEligible,
                                         String windowStart,
                                         String windowEnd,
                                         int queryCount,
                                         int uniqueUrlCount,
                                         int returnedCandidateCount,
                                         boolean truncatedForInlineBudget,
                                         List<CompactDiscoveryCandidate> candidates,
                                         int successfulQueryCount,
                                         int cachedQueryCount,
                                         @JsonInclude(JsonInclude.Include.NON_EMPTY)
                                         List<CompactQueryExecution> failedExecutions,
                                         int structuredSourceCount,
                                         String observedAt,
                                         String rankingPolicyVersion,
                                         String snapshotHash,
                                         String rankingHash,
                                         Map<String, Integer> diagnostics,
                                         Long discoveryRunId,
                                         boolean snapshotPersisted,
                                         String message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CompactDiscoveryCandidate(int rank,
                                             String title,
                                             String url,
                                             String sourceClass,
                                             String publishedAtHint,
                                             String temporalStatus,
                                             String selectionLane) {
    }

    private record CompactQueryExecution(String family,
                                         String providerId,
                                         int resultCount,
                                         String status) {
    }

    private Long resolveWorkspace(@Nullable ToolContext ctx) {
        Long workspaceId = ChatOrigin.from(ctx).workspaceId();
        if (workspaceId != null) return workspaceId;
        String conversationId = ToolExecutionContext.conversationId(ctx);
        if (conversationId != null && !conversationId.isBlank()) {
            ConversationEntity conversation = conversationService.findByConversationId(conversationId);
            if (conversation != null && conversation.getWorkspaceId() != null) return conversation.getWorkspaceId();
        }
        throw new IllegalStateException("AI news event mutation requires an explicit workspace context");
    }

    private static boolean requiresWorkspace(String op) {
        return switch (op) {
            case "discover", "window_summary", "capture_source", "read_capture", "upsert",
                    "list", "get", "capture_official", "mark_verified", "dismiss", "mark_published",
                    "archive", "link_run", "link_content", "link_wiki" -> true;
            default -> false;
        };
    }

    private static boolean requiresHumanOrigin(String op) {
        return switch (op) {
            case "mark_verified", "dismiss", "mark_published", "archive",
                    "link_run", "link_content", "link_wiki" -> true;
            default -> false;
        };
    }

    private static void requireHumanOrigin(@Nullable ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        if (origin.cronOrigin()
                || origin.requesterId() == null || origin.requesterId().isBlank()
                || "system".equalsIgnoreCase(origin.requesterId())
                || "anonymous".equalsIgnoreCase(origin.requesterId())
                || "anonymousUser".equalsIgnoreCase(origin.requesterId())
                // A webchat visitor token is not an authenticated editorial
                // identity. IM sender ids remain attributable human origins.
                || (origin.requesterUserId() == null
                    && (origin.channelType() == null || origin.channelType().isBlank()
                        || "web".equalsIgnoreCase(origin.channelType())))) {
            throw new IllegalStateException("AI news editorial mutation requires an authenticated human origin");
        }
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

    private static Integer parseOptionalLimit(String value) {
        if (value == null || value.isBlank()) return null;
        return parseSourceLimit(value);
    }

    private static Double parseConfidence(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            double confidence = Double.parseDouble(value.trim());
            if (!Double.isFinite(confidence) || confidence < 0.0D || confidence > 1.0D) {
                throw new IllegalArgumentException("relationConfidence must be a number between 0 and 1");
            }
            return confidence;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("relationConfidence must be a number between 0 and 1");
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

    private static Instant parseRequiredInstant(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for this action");
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be an ISO-8601 UTC timestamp");
        }
    }

    private static Integer parseOffset(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            int offset = Integer.parseInt(value.trim());
            if (offset < 0) throw new IllegalArgumentException("startOffset must be >= 0");
            return offset;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("startOffset must be an integer >= 0");
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}

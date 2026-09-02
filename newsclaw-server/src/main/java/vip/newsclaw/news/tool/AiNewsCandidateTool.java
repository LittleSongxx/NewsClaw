package vip.newsclaw.news.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.news.model.AiNewsCandidateEntity;
import vip.newsclaw.news.model.AiNewsCandidatePromotionRequest;
import vip.newsclaw.news.model.AiNewsScanRunEntity;
import vip.newsclaw.news.service.AiNewsCandidatePipelineService;
import vip.newsclaw.news.service.AiNewsCandidatePromotionService;
import vip.newsclaw.news.service.AiNewsScanOrchestrator;
import vip.newsclaw.tool.ConcurrencyUnsafe;
import vip.newsclaw.tool.builtin.ToolExecutionContext;
import vip.newsclaw.workspace.conversation.ConversationService;
import vip.newsclaw.workspace.conversation.model.ConversationEntity;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;

/** Small Agent facade for the backend-owned candidate pipeline. */
@Component("aiNewsCandidateTool")
public class AiNewsCandidateTool {

    /** Scheduler cadence is 15 minutes; allow one missed tick before a run is stale. */
    private static final Duration FRESH_RUN_MAX_LAG = Duration.ofMinutes(30);
    /** Only terminal runs can be reused by the daily Agent invocation. */
    private static final Set<String> TERMINAL_RUN_STATUSES =
            Set.of("COMPLETED", "FAILED", "CANCELLED");

    private final AiNewsScanOrchestrator orchestrator;
    private final AiNewsCandidatePipelineService pipelineService;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    /** Optional for extension deployments that only expose candidate queries. */
    @Autowired(required = false)
    private AiNewsCandidatePromotionService promotionService;

    public AiNewsCandidateTool(AiNewsScanOrchestrator orchestrator,
                               AiNewsCandidatePipelineService pipelineService,
                               ConversationService conversationService,
                               ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.pipelineService = pipelineService;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    @ConcurrencyUnsafe("one backend scan owns its durable run and capture transitions")
    @Tool(name = "ai_news_scan", description = "启动一次后端拥有的 AI 新闻候选扫描。"
            + "扫描会自行完成多路召回、候选落库、排序和已启用的正文抓取；"
            + "不需要模型复制 capture ID 或推进内部状态。候选流水线 feature flag 未开启时会明确返回错误。")
    public String scan(
            @ToolParam(description = "主题；为空时使用 artificial intelligence", required = false)
            String topic,
            @ToolParam(description = "扫描窗口起点，ISO-8601 UTC，闭区间") String windowStart,
            @ToolParam(description = "扫描窗口终点，ISO-8601 UTC，开区间") String windowEnd,
            @ToolParam(description = "最多选择的候选数，1-50", required = false)
            Integer maxCandidates,
            @Nullable ToolContext context) {
        try {
            var summary = orchestrator.run(resolveWorkspace(context), topic,
                    Instant.parse(required(windowStart, "windowStart")),
                    Instant.parse(required(windowEnd, "windowEnd")),
                    maxCandidates, "agent");
            return json(new ScanOutput(summary.run().getId(), summary.run().getRunStatus(),
                    value(summary.run().getRawResultCount()),
                    value(summary.run().getUniqueCandidateCount()),
                    value(summary.run().getSelectedCandidateCount()),
                    value(summary.run().getCaptureSuccessCount()),
                    value(summary.run().getCaptureFailureCount()),
                    value(summary.run().getProviderDisabledCount()), summary.scorecard()));
        } catch (Exception error) {
            return failure(error);
        }
    }

    @Tool(name = "ai_news_query", description = "查询 AI 新闻候选及一次扫描的业务记分卡。"
            + "省略 scanRunId 时返回工作区最近扫描及其窗口/状态/记分卡，便于复用调度器结果；"
            + "同时返回 candidatePipelineEnabled 和 latestRun.inProgress；只有已启用、latestRun 不在进行中且没有新鲜扫描时才需要调用 ai_news_scan。"
            + "进行中的 RUNNING/CANDIDATES_PERSISTED/CAPTURE_PENDING 扫描应等待后重查，不能重复启动。支持按 scan、provider、选择/抓取/审核状态、"
            + "是否 provider 独有和观测时间筛选；返回简短的人类可读候选，不返回正文或要求模型处理 capture ID。")
    public String query(
            @ToolParam(description = "扫描 id；为空时查询工作区最近候选", required = false)
            Long scanRunId,
            @ToolParam(description = "provider id", required = false) String providerId,
            @ToolParam(description = "SELECTED/NOT_SELECTED/REJECTED", required = false)
            String selectionStatus,
            @ToolParam(description = "PENDING/CAPTURING/RETRYABLE/SUCCESS/FAILED", required = false)
            String captureStatus,
            @ToolParam(description = "PENDING/ACCEPTED/REJECTED", required = false)
            String reviewStatus,
            @ToolParam(description = "只看单一 provider 独有候选", required = false)
            Boolean marginalOnly,
            @ToolParam(description = "最后观测时间下界，ISO-8601 UTC", required = false)
            String seenAfter,
            @ToolParam(description = "最后观测时间上界，ISO-8601 UTC", required = false)
            String seenBefore,
            @ToolParam(description = "页码，默认 1", required = false) Integer page,
            @ToolParam(description = "每页数量，默认 20，最多 100", required = false) Integer size,
            @Nullable ToolContext context) {
        try {
            Long workspaceId = resolveWorkspace(context);
            AiNewsCandidatePipelineService.RunSummary summary = scanRunId == null
                    ? pipelineService.latestRun(workspaceId)
                    : pipelineService.inspectRun(workspaceId, scanRunId);
            // Omitting scanRunId means "the latest run", never "all historical
            // candidates".  An empty workspace therefore returns an explicit
            // empty page rather than leaking stale rows into a daily digest.
            Long effectiveScanRunId = scanRunId != null ? scanRunId
                    : summary == null || summary.run() == null ? null : summary.run().getId();
            int requestedPage = page == null ? 1 : Math.max(1, page);
            int requestedSize = size == null ? 20 : Math.min(Math.max(1, size), 100);
            List<CandidateOutput> rows;
            long current = requestedPage;
            long actualSize = requestedSize;
            long total = 0L;
            if (effectiveScanRunId == null) {
                rows = List.of();
            } else {
                var candidates = pipelineService.candidates(workspaceId,
                        requestedPage, requestedSize, effectiveScanRunId, providerId,
                        selectionStatus, captureStatus, reviewStatus, marginalOnly,
                        instant(seenAfter), instant(seenBefore));
                rows = candidates.getRecords().stream()
                        .map(AiNewsCandidateTool::candidate).toList();
                current = candidates.getCurrent();
                actualSize = candidates.getSize();
                total = candidates.getTotal();
            }
            return json(new QueryOutput(current, actualSize,
                    total, rows, summary == null ? null : summary.scorecard(),
                    summary == null ? List.of() : summary.providers(),
                    summary == null ? null : run(summary.run()), orchestrator.enabled()));
        } catch (Exception error) {
            return failure(error);
        }
    }

    @ConcurrencyUnsafe("review updates a workspace candidate state")
    @Tool(name = "ai_news_review", description = "采用或拒绝一个 AI 新闻候选。"
            + "只更新候选审核状态；不会自动发布，也不会要求模型创建证据或复制 capture ID。")
    public String review(
            @ToolParam(description = "ai_news_query 返回的候选 id") Long candidateId,
            @ToolParam(description = "ACCEPTED 或 REJECTED") String decision,
            @ToolParam(description = "简短审核理由", required = false) String reason,
            @Nullable ToolContext context) {
        try {
            ChatOrigin origin = requireHumanOrigin(context);
            AiNewsCandidateEntity candidate = pipelineService.review(resolveWorkspace(context),
                    candidateId, decision, reason, humanActor(origin), humanOrigin(origin));
            return json(candidate(candidate));
        } catch (Exception error) {
            return failure(error);
        }
    }

    @ConcurrencyUnsafe("promotion binds one candidate's server-owned capture to one event")
    @Tool(name = "ai_news_promote", description = "在人工 ACCEPTED 且 capture SUCCESS 的 selected 候选上创建一个待核验 AI 新闻事件。"
            + "后端从候选所属扫描读取冻结时间窗并绑定已有 capture；不会自动 verify、启动内容生产或发布。"
            + "candidateId 来自 ai_news_query，claim 必须是单一原子事实，quote 必须逐字来自候选的已抓取正文；没有满足条件时应保留候选并报告阻断。")
    public String promote(
            @ToolParam(description = "ai_news_query 返回的候选 id") Long candidateId,
            @ToolParam(description = "一个不超过 512 字符的原子事实声明") String claim,
            @ToolParam(description = "逐字来自后端 capture 正文的引用片段") String quote,
            @ToolParam(description = "分类：model/product/open_source/security/infrastructure/partnership/funding/robotics/industry/policy", required = false) String category,
            @ToolParam(description = "实体标签，逗号分隔", required = false) String entities,
            @ToolParam(description = "quote 对 claim 的关系：entails/contradicts/partial/unrelated/hedged/unknown", required = false) String semanticRelation,
            @ToolParam(description = "关系置信度，0-1", required = false) Double relationConfidence,
            @Nullable ToolContext context) {
        try {
            requireHumanOrigin(context);
            if (promotionService == null) throw new IllegalStateException("candidate promotion is unavailable");
            return json(promotionService.promote(resolveWorkspace(context), candidateId,
                    new AiNewsCandidatePromotionRequest(claim, quote, category,
                            splitEntities(entities), semanticRelation, relationConfidence)));
        } catch (Exception error) {
            return failure(error);
        }
    }

    /** Candidate adoption/promotion is an editorial action, never a cron/model action. */
    private static ChatOrigin requireHumanOrigin(@Nullable ToolContext context) {
        ChatOrigin origin = ChatOrigin.from(context);
        if (origin.cronOrigin() || origin.requesterId() == null
                || origin.requesterId().isBlank()
                || "system".equalsIgnoreCase(origin.requesterId())
                || "anonymous".equalsIgnoreCase(origin.requesterId())
                || "anonymousUser".equalsIgnoreCase(origin.requesterId())) {
            throw new IllegalStateException("candidate adoption requires an authenticated human origin");
        }
        // Web console requests carry a NewsClaw user id. IM requests carry a
        // channel sender id; both are explicit human origins, unlike cron.
        if (origin.requesterUserId() == null
                && (origin.channelType() == null || origin.channelType().isBlank())) {
            throw new IllegalStateException("candidate adoption origin is not attributable");
        }
        return origin;
    }

    private static String humanActor(ChatOrigin origin) {
        return origin.requesterUserId() == null
                ? origin.requesterId() : "user:" + origin.requesterUserId();
    }

    private static String humanOrigin(ChatOrigin origin) {
        if (origin.requesterUserId() != null) return "HUMAN_WEB";
        String channel = origin.channelType() == null ? "IM"
                : origin.channelType().trim().toUpperCase(Locale.ROOT);
        return "HUMAN_" + (channel.length() > 24 ? channel.substring(0, 24) : channel);
    }

    private Long resolveWorkspace(@Nullable ToolContext context) {
        Long workspaceId = ChatOrigin.from(context).workspaceId();
        if (workspaceId != null) return workspaceId;
        String conversationId = ToolExecutionContext.conversationId(context);
        if (conversationId != null && !conversationId.isBlank()) {
            ConversationEntity conversation = conversationService.findByConversationId(conversationId);
            if (conversation != null && conversation.getWorkspaceId() != null) {
                return conversation.getWorkspaceId();
            }
        }
        throw new IllegalStateException("candidate pipeline requires an explicit workspace context");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static CandidateOutput candidate(AiNewsCandidateEntity row) {
        return new CandidateOutput(row.getId(), row.getTitle(), row.getCanonicalUrl(),
                row.getProviderId(), row.getQueryLane(), row.getProviderRank(),
                row.getPublishedAtHint(), row.getSelectionStatus(), row.getSelectionReason(),
                row.getCaptureStatus(), row.getFailureReason(), row.getReviewStatus(),
                row.getReviewReason(), row.getEventId(), text(row.getPromotedAt()),
                row.getReviewedBy(), text(row.getReviewedAt()), row.getReviewOrigin());
    }

    private static RunOutput run(AiNewsScanRunEntity row) {
        return new RunOutput(row.getId(), row.getRunStatus(),
                text(row.getWindowStart()), text(row.getWindowEnd()),
                text(row.getStartedAt()), text(row.getFinishedAt()), isInProgress(row), isFresh(row));
    }

    private static boolean isInProgress(AiNewsScanRunEntity row) {
        if (row == null) return false;
        if (row.getRunStatus() == null || row.getRunStatus().isBlank()) return true;
        // Unknown non-terminal states fail closed as in-progress.  This keeps
        // a newly introduced status from causing a duplicate scan.
        return !TERMINAL_RUN_STATUSES.contains(row.getRunStatus().trim().toUpperCase(Locale.ROOT));
    }

    private static boolean isFresh(AiNewsScanRunEntity row) {
        if (row == null || isInProgress(row) || row.getWindowEnd() == null
                || row.getRunStatus() == null || !"COMPLETED".equalsIgnoreCase(row.getRunStatus())) {
            return false;
        }
        Instant end = row.getWindowEnd().toInstant(ZoneOffset.UTC);
        return !end.isBefore(Instant.now().minus(FRESH_RUN_MAX_LAG));
    }

    private static String text(java.time.LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }

    private static String failure(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return "Error: " + message.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value.trim());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static List<String> splitEntities(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[,，]"))
                .map(String::trim).filter(item -> !item.isBlank()).limit(32).toList();
    }

    private record ScanOutput(Long scanId,
                              String status,
                              int rawResults,
                              int uniqueCandidates,
                              int selectedCandidates,
                              int captureSuccesses,
                              int captureFailures,
                              int disabledProviders,
                              AiNewsCandidatePipelineService.Scorecard scorecard) {
    }

    private record QueryOutput(long page,
                               long size,
                               long total,
                               List<CandidateOutput> candidates,
                               AiNewsCandidatePipelineService.Scorecard scorecard,
                               List<AiNewsCandidatePipelineService.ProviderYield> providers,
                               RunOutput latestRun,
                               boolean candidatePipelineEnabled) {
    }

    private record RunOutput(Long scanId,
                             String status,
                             String windowStart,
                             String windowEnd,
                             String startedAt,
                             String finishedAt,
                             boolean inProgress,
                             boolean fresh) {
    }

    private record CandidateOutput(Long candidateId,
                                   String title,
                                   String url,
                                   String provider,
                                   String queryLane,
                                   Integer providerRank,
                                   String publishedAtHint,
                                   String selectionStatus,
                                   String selectionReason,
                                   String captureStatus,
                                   String captureFailure,
                                   String reviewStatus,
                                   String reviewReason,
                                   Long eventId,
                                   String promotedAt,
                                   String reviewedBy,
                                   String reviewedAt,
                                   String reviewOrigin) {
    }
}

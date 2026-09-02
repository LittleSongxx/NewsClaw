package vip.newsclaw.news.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.newsclaw.common.result.R;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsCandidateEntity;
import vip.newsclaw.news.model.AiNewsCandidatePromotionRequest;
import vip.newsclaw.news.model.AiNewsScanRunEntity;
import vip.newsclaw.news.service.AiNewsCandidatePipelineService;
import vip.newsclaw.news.service.AiNewsCandidatePromotionService;
import vip.newsclaw.news.service.AiNewsScanOrchestrator;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

import java.time.Instant;

/** Workspace-scoped scan, candidate and review facade for the V213 shadow mainline. */
@Tag(name = "AI 新闻候选流水线")
@RestController
@RequestMapping("/api/v1/ai-news/candidate-pipeline")
public class AiNewsCandidatePipelineController {

    private final AiNewsScanOrchestrator orchestrator;
    private final AiNewsCandidatePipelineService pipelineService;
    private final AiNewsCandidatePromotionService promotionService;

    public AiNewsCandidatePipelineController(AiNewsScanOrchestrator orchestrator,
                                             AiNewsCandidatePipelineService pipelineService,
                                             AiNewsCandidatePromotionService promotionService) {
        this.orchestrator = orchestrator;
        this.pipelineService = pipelineService;
        this.promotionService = promotionService;
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "无 LLM 参与地执行一次候选扫描")
    @PostMapping("/scans")
    public R<AiNewsCandidatePipelineService.RunSummary> scan(
            @RequestHeader("X-Workspace-Id") Long workspaceId,
            @RequestBody ScanRequest request) {
        workspaceId = requireWorkspace(workspaceId);
        if (request == null || request.windowStart() == null || request.windowEnd() == null) {
            throw new NewsClawException(400, "windowStart and windowEnd are required");
        }
        try {
            return R.ok(orchestrator.run(workspaceId, request.topic(),
                    Instant.parse(request.windowStart()), Instant.parse(request.windowEnd()),
                    request.maxCandidates(), "manual"));
        } catch (java.time.format.DateTimeParseException error) {
            throw new NewsClawException(400, "windowStart/windowEnd must be ISO-8601 instants");
        }
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "分页查看候选扫描及漏斗计数")
    @GetMapping("/scans")
    public R<IPage<AiNewsScanRunEntity>> scans(
            @RequestHeader("X-Workspace-Id") Long workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return R.ok(pipelineService.scans(requireWorkspace(workspaceId), page, size, status));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "查看一次扫描的 provider 边际贡献与四项记分卡")
    @GetMapping("/scans/{scanRunId}")
    public R<AiNewsCandidatePipelineService.RunSummary> scanSummary(
            @PathVariable Long scanRunId,
            @RequestHeader("X-Workspace-Id") Long workspaceId) {
        return R.ok(pipelineService.inspectRun(requireWorkspace(workspaceId), scanRunId));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "按扫描、provider 和流水线状态查询候选")
    @GetMapping("/candidates")
    public R<IPage<AiNewsCandidateEntity>> candidates(
            @RequestHeader("X-Workspace-Id") Long workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long scanRunId,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) String selectionStatus,
            @RequestParam(required = false) String captureStatus,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) Boolean marginalOnly,
            @RequestParam(required = false) String seenAfter,
            @RequestParam(required = false) String seenBefore) {
        try {
            return R.ok(pipelineService.candidates(requireWorkspace(workspaceId), page, size, scanRunId, providerId,
                    selectionStatus, captureStatus, reviewStatus, marginalOnly,
                    optionalInstant(seenAfter), optionalInstant(seenBefore)));
        } catch (java.time.format.DateTimeParseException error) {
            throw new NewsClawException(400, "seenAfter/seenBefore must be ISO-8601 instants");
        }
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "人工采用或拒绝一个候选")
    @PostMapping("/candidates/{candidateId}/review")
    public R<AiNewsCandidateEntity> review(
            @PathVariable Long candidateId,
            @RequestHeader("X-Workspace-Id") Long workspaceId,
            @RequestBody ReviewRequest request,
            Authentication authentication) {
        if (request == null) throw new NewsClawException(400, "review request is required");
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
            throw new NewsClawException(401, "candidate review requires an authenticated operator");
        }
        return R.ok(pipelineService.review(requireWorkspace(workspaceId), candidateId,
                request.decision(), request.reason(), authentication.getName(), "HUMAN_WEB"));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "将已采用且已抓取的候选形成待核验事件")
    @PostMapping("/candidates/{candidateId}/promote")
    public R<vip.newsclaw.news.model.AiNewsEventEntity> promote(
            @PathVariable Long candidateId,
            @RequestHeader("X-Workspace-Id") Long workspaceId,
            @RequestBody AiNewsCandidatePromotionRequest request) {
        if (request == null) {
            throw new NewsClawException(400, "promotion request is required");
        }
        return R.ok(promotionService.promote(requireWorkspace(workspaceId), candidateId, request));
    }

    public record ScanRequest(String topic,
                              String windowStart,
                              String windowEnd,
                              Integer maxCandidates) {
    }

    public record ReviewRequest(String decision, String reason) {
    }

    private static long requireWorkspace(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new NewsClawException(400, "X-Workspace-Id must be a positive workspace id");
        }
        return workspaceId;
    }

    private static Instant optionalInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value.trim());
    }
}

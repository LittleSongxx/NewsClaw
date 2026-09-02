package vip.newsclaw.news.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
import vip.newsclaw.news.model.AiNewsEventClusterDetail;
import vip.newsclaw.news.model.AiNewsEventClusterEntity;
import vip.newsclaw.news.model.AiNewsEventClusterMergeRequest;
import vip.newsclaw.news.model.AiNewsEventClusterReviewEntity;
import vip.newsclaw.news.model.AiNewsEventClusterReviewRequest;
import vip.newsclaw.news.model.AiNewsEventClusterSplitRequest;
import vip.newsclaw.news.service.AiNewsEventClusterService;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/** Workspace-scoped review surface for versioned event identity decisions. */
@Tag(name = "AI 动态事件聚类")
@RestController
@RequestMapping("/api/v1/ai-news/clusters")
@RequiredArgsConstructor
public class AiNewsEventClusterController {

    private final AiNewsEventClusterService clusterService;

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "分页查看当前/历史事件簇")
    @GetMapping
    public R<IPage<AiNewsEventClusterEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return R.ok(clusterService.page(workspaceId, page, size, status));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "查看事件簇当前成员、版本、lineage 与复核记录")
    @GetMapping("/{id}")
    public R<AiNewsEventClusterDetail> get(
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(clusterService.detail(workspaceId, id));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "查看低置信聚类复核队列")
    @GetMapping("/reviews")
    public R<List<AiNewsEventClusterReviewEntity>> reviews(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "100") int limit) {
        return R.ok(clusterService.reviews(workspaceId, status, limit));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "人工合并 active 事件簇并记录 lineage")
    @PostMapping("/merge")
    public R<AiNewsEventClusterDetail> merge(
            @RequestBody AiNewsEventClusterMergeRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(clusterService.merge(workspaceId,
                request == null ? null : request.clusterIds(), currentOperator(),
                request == null ? null : request.note()));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "人工拆分事件簇成员并记录 lineage")
    @PostMapping("/split")
    public R<AiNewsEventClusterDetail> split(
            @RequestBody AiNewsEventClusterSplitRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(clusterService.split(workspaceId,
                request == null ? null : request.clusterId(),
                request == null ? null : request.eventIds(), currentOperator(),
                request == null ? null : request.note()));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "批准合并建议或确认保持分离")
    @PostMapping("/reviews/{id}/resolve")
    public R<AiNewsEventClusterReviewEntity> resolve(
            @PathVariable Long id,
            @RequestBody AiNewsEventClusterReviewRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(clusterService.resolveReview(workspaceId, id,
                request == null ? null : request.decision(), currentOperator(),
                request == null ? null : request.note()));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "为迁移前事件补建版本化事件簇（有界批次）")
    @PostMapping("/backfill")
    public R<AiNewsEventClusterService.BackfillResult> backfill(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "200") int limit) {
        return R.ok(clusterService.backfill(workspaceId, limit));
    }

    private static String currentOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
            throw new NewsClawException(401, "未识别聚类复核操作者");
        }
        return authentication.getName();
    }
}

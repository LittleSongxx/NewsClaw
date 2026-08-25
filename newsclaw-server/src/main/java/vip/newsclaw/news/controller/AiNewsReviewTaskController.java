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
import vip.newsclaw.news.model.AiNewsReviewResolveRequest;
import vip.newsclaw.news.model.AiNewsReviewTaskEntity;
import vip.newsclaw.news.service.AiNewsReviewRoutingService;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

/** Observable, workspace-scoped queue for deterministic AI-news human review. */
@Tag(name = "AI 动态人工复核")
@RestController
@RequestMapping("/api/v1/ai-news/reviews")
@RequiredArgsConstructor
public class AiNewsReviewTaskController {

    private final AiNewsReviewRoutingService reviewRoutingService;

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "分页查询 AI 动态人工复核队列")
    @GetMapping
    public R<IPage<AiNewsReviewTaskEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return R.ok(reviewRoutingService.page(workspaceId, page, size, status));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "查看事件对应的 AI 动态人工复核任务")
    @GetMapping("/{eventId}")
    public R<AiNewsReviewTaskEntity> get(
            @PathVariable Long eventId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(reviewRoutingService.get(workspaceId, eventId));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "记录已核验事件的人工复核结论")
    @PostMapping("/{eventId}/resolve")
    public R<AiNewsReviewTaskEntity> resolve(
            @PathVariable Long eventId,
            @RequestBody AiNewsReviewResolveRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(reviewRoutingService.resolve(workspaceId, eventId, currentOperator(),
                request == null ? null : request.note()));
    }

    private static String currentOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
            throw new NewsClawException(401, "未识别人工复核操作者");
        }
        return authentication.getName();
    }
}

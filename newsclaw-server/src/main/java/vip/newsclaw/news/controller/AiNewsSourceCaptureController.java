package vip.newsclaw.news.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.newsclaw.common.result.R;
import vip.newsclaw.news.model.AiNewsSourceCaptureRequest;
import vip.newsclaw.news.service.AiNewsSourceCaptureService;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

/** Workspace-scoped read-only source snapshots for evidence-bound event writes. */
@Tag(name = "AI 动态来源快照")
@RestController
@RequestMapping("/api/v1/ai-news/source-captures")
@RequiredArgsConstructor
public class AiNewsSourceCaptureController {

    private final AiNewsSourceCaptureService captureService;

    @RequireWorkspaceRole("member")
    @Operation(summary = "抓取公开来源并创建不可变证据快照")
    @PostMapping
    public R<AiNewsSourceCaptureService.CaptureSummary> capture(
            @RequestBody AiNewsSourceCaptureRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(captureService.capture(workspaceId,
                request == null ? null : request.sourceUrl()));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "分页读取来源快照的规范化正文")
    @GetMapping("/{id}")
    public R<AiNewsSourceCaptureService.CapturePage> read(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") Integer startOffset,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(captureService.read(workspaceId, id, startOffset));
    }
}

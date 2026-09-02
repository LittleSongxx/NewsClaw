package vip.newsclaw.content.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vip.newsclaw.common.result.R;
import vip.newsclaw.content.model.ContentItemEntity;
import vip.newsclaw.content.service.ContentItemService;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

import java.util.Map;

/**
 * Content calendar API — lists produced 公众号 / 小红书 pieces and their
 * lifecycle status, so operators can see what's drafted / packaged / published /
 * pending. The explicit acknowledgement endpoint only records human approval;
 * platform publication still requires a non-empty external receipt.
 */
@Tag(name = "内容日历")
@RestController
@RequestMapping("/api/v1/content-items")
@RequiredArgsConstructor
public class ContentItemController {

    private final ContentItemService contentItemService;

    @Operation(summary = "内容日历分页列表")
    @RequireWorkspaceRole("viewer")
    @GetMapping
    public R<IPage<ContentItemEntity>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String status,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(contentItemService.page(workspaceId, page, size, platform, status));
    }

    @Operation(summary = "内容日历状态计数")
    @RequireWorkspaceRole("viewer")
    @GetMapping("/summary")
    public R<Map<String, Long>> summary(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(contentItemService.summary(workspaceId));
    }

    @Operation(summary = "人工确认内容工件")
    @RequireWorkspaceRole("member")
    @PostMapping("/{id}/acknowledge")
    public R<Boolean> acknowledge(
            @PathVariable Long id,
            @RequestBody AcknowledgeRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        String hash = request == null ? null : request.artifactHash();
        return R.ok(contentItemService.acknowledge(workspaceId, id, hash));
    }

    public record AcknowledgeRequest(String artifactHash) {}
}

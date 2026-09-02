package vip.newsclaw.news.workflow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import vip.newsclaw.common.result.R;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

/** Operator-facing preview/install surface for the NewsClaw vertical template. */
@Tag(name = "AI 动态工作流模板")
@RestController
@RequestMapping("/api/v1/ai-news/workflow-template")
@RequiredArgsConstructor
public class AiNewsWorkflowTemplateController {

    private final AiNewsWorkflowTemplateService templateService;

    @Operation(summary = "预览 AI 动态内容运营闭环模板")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<AiNewsWorkflowTemplate> preview(@RequestHeader("X-Workspace-Id") long workspaceId) {
        return R.ok(templateService.preview(workspaceId));
    }

    @Operation(summary = "安装 AI 动态工作流草稿和禁用触发器")
    @PostMapping("/install")
    @RequireWorkspaceRole("admin")
    public R<AiNewsWorkflowTemplateService.InstallationResult> install(
            @RequestHeader("X-Workspace-Id") long workspaceId,
            @RequestBody(required = false) InstallRequest request,
            Authentication authentication) {
        Long authenticatedUserId = authentication != null && authentication.getDetails() instanceof Number n
                ? n.longValue() : null;
        return R.ok(templateService.install(workspaceId,
                authenticatedUserId,
                request != null && Boolean.TRUE.equals(request.enableTriggers())));
    }

    public record InstallRequest(Long createdBy, Boolean enableTriggers) {
    }
}

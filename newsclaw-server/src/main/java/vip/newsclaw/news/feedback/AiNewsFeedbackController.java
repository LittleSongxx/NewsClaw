package vip.newsclaw.news.feedback;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.newsclaw.common.result.R;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

/** Human badcase/feedback entry point for the NewsClaw learning loop. */
@Tag(name = "AI 动态反馈")
@RestController
@RequestMapping("/api/v1/ai-news/feedback")
@RequiredArgsConstructor
public class AiNewsFeedbackController {

    private final AiNewsFeedbackService feedbackService;

    @Operation(summary = "记录 AI 动态 badcase，并可生成待审 Skill 提案")
    @PostMapping
    @RequireWorkspaceRole("member")
    public R<AiNewsFeedbackService.FeedbackResult> submit(
            @RequestHeader("X-Workspace-Id") Long workspaceId,
            @RequestBody AiNewsFeedbackRequest request) {
        return R.ok(feedbackService.submit(workspaceId, request));
    }
}

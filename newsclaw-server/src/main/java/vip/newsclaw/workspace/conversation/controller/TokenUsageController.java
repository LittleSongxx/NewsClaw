package vip.newsclaw.workspace.conversation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import vip.newsclaw.common.result.R;
import vip.newsclaw.workspace.conversation.TokenUsageService;
import vip.newsclaw.workspace.conversation.vo.TokenUsageSummaryVO;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

import java.time.LocalDate;

/**
 * Token Usage 统计接口
 *
 * @author NewsClaw Team
 */
@Tag(name = "Token Usage 统计")
@RestController
@RequestMapping("/api/v1/token-usage")
@RequiredArgsConstructor
public class TokenUsageController {

    private final TokenUsageService tokenUsageService;

    @Operation(summary = "获取 Token 使用统计")
    @GetMapping
    @RequireWorkspaceRole("member")
    public R<TokenUsageSummaryVO> getSummary(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String providerId) {
        long ws = workspaceId == null || workspaceId <= 0 ? 1L : workspaceId;
        return R.ok(tokenUsageService.getSummary(startDate, endDate, modelName, providerId, ws));
    }
}

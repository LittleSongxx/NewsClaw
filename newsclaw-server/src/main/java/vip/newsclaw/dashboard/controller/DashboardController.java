package vip.newsclaw.dashboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.newsclaw.common.result.R;
import vip.newsclaw.cron.model.CronJobEntity;
import vip.newsclaw.cron.repository.CronJobMapper;
import vip.newsclaw.dashboard.model.CronJobRunEntity;
import vip.newsclaw.dashboard.service.CronJobRunService;
import vip.newsclaw.dashboard.service.DashboardService;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * Dashboard 统计接口
 *
 * @author NewsClaw Team
 */
@Tag(name = "Dashboard")
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final CronJobRunService cronJobRunService;
    private final CronJobMapper cronJobMapper;

    @Operation(summary = "获取概览统计")
    @GetMapping("/overview")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> overview(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(dashboardService.getOverview(resolveWorkspace(workspaceId)));
    }

    @Operation(summary = "获取日用量趋势")
    @GetMapping("/trend")
    @RequireWorkspaceRole("member")
    public R<List<Map<String, Object>>> trend(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "30") int days) {
        return R.ok(dashboardService.getTrend(resolveWorkspace(workspaceId), Math.min(days, 90)));
    }

    @Operation(summary = "获取 CronJob 执行历史")
    @GetMapping("/cron-runs/{cronJobId}")
    @RequireWorkspaceRole("member")
    public R<List<CronJobRunEntity>> cronJobRuns(
            @PathVariable Long cronJobId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "20") int limit) {
        // Verify the cron job belongs to the caller's workspace. Checked
        // against the job's own workspace_id so agent-less system jobs
        // (e.g. wiki_process) verify the same way as agent-bound jobs.
        CronJobEntity job = cronJobMapper.selectById(cronJobId);
        if (job == null) {
            return R.fail(404, "Cron job not found");
        }
        long wsId = resolveWorkspace(workspaceId);
        long jobWorkspaceId = job.getWorkspaceId() == null ? 1L : job.getWorkspaceId();
        if (jobWorkspaceId != wsId) {
            throw new NewsClawException("err.common.wrong_workspace", 403, "资源不属于当前工作区");
        }
        return R.ok(cronJobRunService.listByJobId(cronJobId, Math.min(Math.max(limit, 1), 100)));
    }

    @Operation(summary = "获取最近执行记录（当前 workspace 关联的 CronJob）")
    @GetMapping("/cron-runs")
    @RequireWorkspaceRole("member")
    public R<List<CronJobRunEntity>> recentRuns(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "20") int limit) {
        long wsId = resolveWorkspace(workspaceId);
        return R.ok(cronJobRunService.listRecentByWorkspace(wsId, Math.min(limit, 100)));
    }

    private static long resolveWorkspace(Long workspaceId) {
        return workspaceId == null || workspaceId <= 0 ? 1L : workspaceId;
    }
}

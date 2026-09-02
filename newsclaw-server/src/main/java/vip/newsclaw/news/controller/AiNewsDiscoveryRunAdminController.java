package vip.newsclaw.news.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.newsclaw.common.result.R;
import vip.newsclaw.news.model.AiNewsDiscoveryRunEntity;
import vip.newsclaw.news.evaluation.AiNewsDiscoveryStabilityEvaluator;
import vip.newsclaw.news.service.AiNewsDiscoveryRunAdminService;
import vip.newsclaw.news.service.AiNewsDiscoverySearchService;
import vip.newsclaw.workspace.core.annotation.RequireGlobalAdmin;

import java.util.List;

/** Admin-only audit API for content-addressed discovery observations. */
@Tag(name = "AI 动态发现快照")
@RestController
@RequestMapping("/api/v1/ai-news/discovery")
public class AiNewsDiscoveryRunAdminController {

    private final AiNewsDiscoveryRunAdminService adminService;

    public AiNewsDiscoveryRunAdminController(AiNewsDiscoveryRunAdminService adminService) {
        this.adminService = adminService;
    }

    @RequireGlobalAdmin
    @Operation(summary = "分页查看发现运行摘要（不加载原始响应）")
    @GetMapping("/runs")
    public R<IPage<AiNewsDiscoveryRunEntity>> runs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) String rankingPolicyVersion) {
        return R.ok(adminService.runs(page, size, workspaceId, rankingPolicyVersion));
    }

    @RequireGlobalAdmin
    @Operation(summary = "查看一次发现运行的逐通道响应、候选与拒绝诊断")
    @GetMapping("/runs/{runId}")
    public R<AiNewsDiscoveryRunAdminService.RunInspection> inspect(@PathVariable Long runId) {
        return R.ok(adminService.inspect(runId));
    }

    @RequireGlobalAdmin
    @Operation(summary = "计算同窗发现运行的 Jaccard/RBO 与时间准入稳定性")
    @GetMapping("/stability")
    public R<AiNewsDiscoveryStabilityEvaluator.StabilityReport> stability(
            @RequestParam List<Long> runIds) {
        return R.ok(adminService.stability(runIds));
    }

    @RequireGlobalAdmin
    @Operation(summary = "离线重放冻结响应并应用当前发现准入/排序策略")
    @PostMapping("/runs/{runId}/replay")
    public R<AiNewsDiscoverySearchService.DiscoveryBatch> replay(
            @PathVariable Long runId,
            @RequestParam(required = false) Integer maxCandidates) {
        return R.ok(adminService.replay(runId, maxCandidates));
    }

    @RequireGlobalAdmin
    @Operation(summary = "执行一次隔离的发现运行并持久化逐通道响应快照")
    @PostMapping("/search")
    public R<AiNewsDiscoverySearchService.DiscoveryBatch> discover(
            @RequestBody DiscoveryRequest request) {
        if (request == null) throw new vip.newsclaw.exception.NewsClawException(
                400, "discovery request is required");
        return R.ok(adminService.discover(request.workspaceId(), request.topic(),
                request.windowStart(), request.windowEnd(), request.maxCandidates()));
    }

    public record DiscoveryRequest(Long workspaceId,
                                   String topic,
                                   String windowStart,
                                   String windowEnd,
                                   Integer maxCandidates) {
    }
}

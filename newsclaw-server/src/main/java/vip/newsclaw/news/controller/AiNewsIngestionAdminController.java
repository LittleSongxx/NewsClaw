package vip.newsclaw.news.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.newsclaw.common.result.R;
import vip.newsclaw.news.model.AiNewsIngestionRunEntity;
import vip.newsclaw.news.model.AiNewsSourceEndpointEntity;
import vip.newsclaw.news.service.AiNewsIngestionAdminService;
import vip.newsclaw.workspace.core.annotation.RequireGlobalAdmin;

/** Admin-only read API for diagnosing structured-source acquisition. */
@Tag(name = "AI 动态摄取账本")
@RestController
@RequestMapping("/api/v1/ai-news/ingestion")
public class AiNewsIngestionAdminController {

    private final AiNewsIngestionAdminService adminService;

    public AiNewsIngestionAdminController(AiNewsIngestionAdminService adminService) {
        this.adminService = adminService;
    }

    @RequireGlobalAdmin
    @Operation(summary = "分页查看结构化来源 endpoint 与持久化轮询游标")
    @GetMapping("/endpoints")
    public R<IPage<AiNewsSourceEndpointEntity>> endpoints(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) Boolean enabled) {
        return R.ok(adminService.endpoints(page, size, providerId, enabled));
    }

    @RequireGlobalAdmin
    @Operation(summary = "分页查看结构化来源摄取 run")
    @GetMapping("/runs")
    public R<IPage<AiNewsIngestionRunEntity>> runs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long endpointId,
            @RequestParam(required = false) String status) {
        return R.ok(adminService.runs(page, size, endpointId, status));
    }

    @RequireGlobalAdmin
    @Operation(summary = "查看一次摄取 run 的 item/version 与 HTTP observation 元数据")
    @GetMapping("/runs/{runId}")
    public R<AiNewsIngestionAdminService.RunInspection> inspectRun(@PathVariable Long runId) {
        return R.ok(adminService.inspectRun(runId));
    }
}

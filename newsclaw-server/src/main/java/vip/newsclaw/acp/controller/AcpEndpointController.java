package vip.newsclaw.acp.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.newsclaw.acp.model.AcpEndpointEntity;
import vip.newsclaw.acp.service.AcpConnectionTester;
import vip.newsclaw.acp.service.AcpEndpointService;
import vip.newsclaw.common.result.R;

import java.util.List;
import java.util.Map;
import vip.newsclaw.workspace.core.annotation.RequireGlobalAdmin;

/**
 * RFC-090 Phase 7 — REST surface for managing ACP endpoints.
 *
 * <p>Mirrors the McpServers controller so the frontend page can be a
 * close cousin of {@code McpServers.vue}. Process registration is global-admin
 * only; rows are additionally scoped to the selected workspace.
 */
@Tag(name = "ACP Endpoints (RFC-090 Phase 7)")
@RestController
@RequestMapping("/api/v1/acp/endpoints")
@RequiredArgsConstructor
public class AcpEndpointController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final AcpEndpointService service;
    private final AcpConnectionTester tester;

    @Operation(summary = "List ACP endpoints")
    @GetMapping
    @RequireGlobalAdmin
    public R<List<AcpEndpointEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.list(workspaceId(workspaceId)));
    }

    @Operation(summary = "Get ACP endpoint by id")
    @GetMapping("/{id}")
    @RequireGlobalAdmin
    public R<AcpEndpointEntity> get(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.get(id, workspaceId(workspaceId)));
    }

    @Operation(summary = "Create a custom ACP endpoint")
    @PostMapping
    @RequireGlobalAdmin
    public R<AcpEndpointEntity> create(@RequestBody AcpEndpointEntity body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        if (body == null) return R.fail(400, "request body is required");
        return R.ok(service.create(body, workspaceId(workspaceId)));
    }

    @Operation(summary = "Update an ACP endpoint")
    @PutMapping("/{id}")
    @RequireGlobalAdmin
    public R<AcpEndpointEntity> update(@PathVariable Long id,
                                        @RequestBody AcpEndpointEntity body,
                                        @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        if (body == null) return R.fail(400, "request body is required");
        return R.ok(service.update(id, body, workspaceId(workspaceId)));
    }

    @Operation(summary = "Delete an ACP endpoint (builtins are protected)")
    @DeleteMapping("/{id}")
    @RequireGlobalAdmin
    public R<Void> delete(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        service.delete(id, workspaceId(workspaceId));
        return R.ok();
    }

    @Operation(summary = "Enable / disable an ACP endpoint")
    @PutMapping("/{id}/toggle")
    @RequireGlobalAdmin
    public R<AcpEndpointEntity> toggle(@PathVariable Long id,
                                        @RequestParam boolean enabled,
                                        @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.toggle(id, enabled, workspaceId(workspaceId)));
    }

    /**
     * Spawn the configured CLI, run {@code initialize} + {@code
     * session/new}, persist the outcome, and return diagnostics.
     */
    @Operation(summary = "Test ACP endpoint connection (initialize handshake)")
    @PostMapping("/{id}/test")
    @RequireGlobalAdmin
    public R<Map<String, Object>> test(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AcpEndpointEntity endpoint = service.get(id, workspaceId(workspaceId));
        return R.ok(tester.testEndpoint(endpoint));
    }

    private static long workspaceId(Long workspaceId) {
        return workspaceId != null ? workspaceId : DEFAULT_WORKSPACE_ID;
    }
}

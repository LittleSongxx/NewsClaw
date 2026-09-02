package vip.newsclaw.wiki.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.newsclaw.common.result.R;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.wiki.hotcache.HotCacheUpdateReason;
import vip.newsclaw.wiki.hotcache.HotCacheUpdateScheduler;
import vip.newsclaw.wiki.hotcache.WikiHotCacheService;
import vip.newsclaw.wiki.model.WikiHotCacheEntity;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;
import vip.newsclaw.wiki.service.WikiKnowledgeBaseService;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

/**
 * Operator endpoints for the KB hot cache: inspect the current snapshot,
 * trigger a manual rebuild, or wipe the cache so the next event-driven
 * rebuild starts from scratch.
 *
 * <p>Sits behind the standard JWT-protected {@code /api/v1} prefix.
 */
@RestController
@RequestMapping("/api/v1/wiki/hot-cache")
@RequiredArgsConstructor
@Tag(name = "Wiki Hot Cache", description = "Operator endpoints for the KB-level recent activity snapshot")
public class WikiHotCacheController {

    private final WikiHotCacheService service;
    private final HotCacheUpdateScheduler scheduler;
    private final WikiKnowledgeBaseService kbService;

    @Operation(summary = "Get the current hot cache snapshot for a KB",
               description = "Returns null data if no rebuild has run yet.")
    @GetMapping("/{kbId}")
    @RequireWorkspaceRole("viewer")
    public R<WikiHotCacheEntity> get(
            @PathVariable Long kbId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return R.ok(service.findByKb(kbId).orElse(null));
    }

    @Operation(summary = "Schedule a manual rebuild of the hot cache",
               description = "Async — the scheduler runs the LLM call on a virtual thread. "
                           + "MANUAL bypasses the debounce window so operator triggers always run. "
                           + "Poll GET to see progress.")
    @PostMapping("/{kbId}/regenerate")
    @RequireWorkspaceRole("member")
    public R<Void> regenerate(
            @PathVariable Long kbId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        scheduler.scheduleRebuild(kbId, HotCacheUpdateReason.MANUAL);
        return R.ok();
    }

    @Operation(summary = "Soft-delete the hot cache row",
               description = "The next event-driven rebuild will create a fresh row. "
                           + "Use this to clear a wedged cache without waiting for the staleness window.")
    @DeleteMapping("/{kbId}")
    @RequireWorkspaceRole("admin")
    public R<Void> reset(
            @PathVariable Long kbId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        service.findByKb(kbId).ifPresent(row -> service.softDelete(row.getId()));
        return R.ok();
    }

    private void verifyKBWorkspace(Long kbId, Long headerWorkspaceId) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) {
            throw new NewsClawException(404, "Knowledge base not found");
        }
        long workspaceId = headerWorkspaceId != null ? headerWorkspaceId : 1L;
        if (kb.getWorkspaceId() == null || !kb.getWorkspaceId().equals(workspaceId)) {
            throw new NewsClawException("err.common.wrong_workspace", 403,
                    "Resource does not belong to current workspace");
        }
    }
}

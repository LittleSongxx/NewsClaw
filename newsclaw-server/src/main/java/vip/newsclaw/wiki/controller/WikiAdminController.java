package vip.newsclaw.wiki.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.newsclaw.common.result.R;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.wiki.dto.WikiFailureItem;
import vip.newsclaw.wiki.job.WikiChunkTokenBackfillJob;
import vip.newsclaw.wiki.service.WikiOverviewService;
import vip.newsclaw.wiki.service.WikiPageService;
import vip.newsclaw.wiki.service.WikiRawMaterialService;
import vip.newsclaw.wiki.service.WikiScaffoldService;
import vip.newsclaw.wiki.service.WikiKnowledgeBaseService;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;
import vip.newsclaw.workspace.core.annotation.RequireGlobalAdmin;

/**
 * RFC-051 follow-up: small set of operator-facing endpoints for things the
 * scheduled jobs / event hooks normally handle automatically. Useful when the
 * cron hasn't fired yet (fresh upgrade), the auto-rebuild was skipped, or you
 * just want to force-refresh during debugging.
 *
 * <p>All endpoints are idempotent and synchronous.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wiki/admin")
@RequiredArgsConstructor
@Tag(name = "Wiki Admin", description = "Operator endpoints for system pages and backfill jobs")
public class WikiAdminController {

    private final WikiScaffoldService scaffoldService;
    private final WikiPageService pageService;
    private final WikiRawMaterialService rawService;
    private final WikiKnowledgeBaseService kbService;

    /** Optional so the controller can boot in environments where the rebuilder isn't wired (e.g. minimal tests). */
    @Autowired(required = false)
    private WikiOverviewService overviewService;

    @Autowired(required = false)
    private WikiChunkTokenBackfillJob backfillJob;

    @Operation(summary = "Ensure overview/log scaffold + rebuild overview stats now",
               description = "Idempotent. Use after manual data imports or when stats look stale.")
    @PostMapping("/kb/{kbId}/rebuild-overview")
    @RequireWorkspaceRole("admin")
    public ResponseEntity<Map<String, Object>> rebuildOverview(
            @PathVariable Long kbId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        Map<String, Object> body = new HashMap<>();
        scaffoldService.ensureScaffold(kbId);
        if (overviewService != null) {
            overviewService.rebuild(kbId);
            body.put("rebuilt", true);
        } else {
            body.put("rebuilt", false);
            body.put("note", "Overview service not wired; only scaffold ensured");
        }
        body.put("kbId", kbId);
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Force-run the token-count backfill batch now",
               description = "Picks up to BATCH_SIZE chunks with token_count IS NULL and fills them. "
                       + "Returns the pending count after the batch so callers can poll.")
    @PostMapping("/backfill-tokens")
    @RequireGlobalAdmin
    public ResponseEntity<Map<String, Object>> backfillTokens() {
        Map<String, Object> body = new HashMap<>();
        if (backfillJob == null) {
            body.put("ok", false);
            body.put("note", "Backfill job not wired");
            return ResponseEntity.ok(body);
        }
        long beforePending = backfillJob.pendingCount();
        backfillJob.runOnce();
        long afterPending = backfillJob.pendingCount();
        body.put("ok", true);
        body.put("pendingBefore", beforePending);
        body.put("pendingAfter", afterPending);
        body.put("filledThisBatch", Math.max(0, beforePending - afterPending));
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "Merge duplicate pages that share a canonical title",
               description = "Heals duplicate rows produced before title-based dedup existed (one concept "
                       + "stored under several LLM-minted slugs). Defaults to a dry run that only reports "
                       + "what would change. Set dryRun=false to apply. concatenate=true (default) appends each "
                       + "loser's body to the winner so no content is lost; concatenate=false keeps only the "
                       + "winner's body. Protected (system/locked) pages always win and are never deleted.")
    @PostMapping("/kb/{kbId}/merge-duplicate-titles")
    @RequireWorkspaceRole("admin")
    public ResponseEntity<Map<String, Object>> mergeDuplicateTitles(
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "true") boolean concatenate,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        Map<String, Object> report = pageService.mergeDuplicateTitles(kbId, dryRun, concatenate);
        return ResponseEntity.ok(report);
    }

    /**
     * Centralized, cross-knowledge-base list of materials needing operator
     * attention (failed / partial / completed-but-degraded). Lets an admin
     * triage background ingest problems without opening each KB in turn —
     * the count behind the sidebar attention badge resolves here.
     *
     * <p>Platform-admin only: it deliberately spans every workspace, so it is
     * gated on {@code ROLE_ADMIN} rather than a per-workspace role.
     */
    @Operation(summary = "跨知识库列出需要关注的处理失败/降级材料（管理员）")
    @GetMapping("/failures")
    public R<List<WikiFailureItem>> listFailures(
            @RequestParam(defaultValue = "100") int limit,
            Authentication auth) {
        requireAdmin(auth);
        return R.ok(rawService.listFailures(limit));
    }

    private void requireAdmin(Authentication auth) {
        if (auth == null) {
            throw new NewsClawException(401, "authentication required");
        }
        boolean admin = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
        if (!admin) {
            throw new NewsClawException(403, "admin only");
        }
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

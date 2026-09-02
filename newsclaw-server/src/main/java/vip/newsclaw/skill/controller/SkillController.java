package vip.newsclaw.skill.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import vip.newsclaw.common.result.R;
import vip.newsclaw.agent.AgentService;
import vip.newsclaw.agent.binding.model.AgentSkillBinding;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;
import vip.newsclaw.agent.binding.repository.AgentSkillBindingMapper;
import vip.newsclaw.agent.binding.service.AgentBindingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.skill.lessons.SkillLessonsService;
import vip.newsclaw.skill.manifest.SkillManifest;
import vip.newsclaw.skill.model.SkillEntity;
import vip.newsclaw.skill.runtime.SkillDependencyChecker;
import vip.newsclaw.skill.runtime.SkillPackageResolver;
import vip.newsclaw.skill.runtime.SkillCatalogSort;
import vip.newsclaw.skill.runtime.SkillCatalogSorter;
import vip.newsclaw.skill.model.SkillFileEntity;
import vip.newsclaw.skill.service.SkillFileService;
import vip.newsclaw.skill.service.SkillService;
import vip.newsclaw.skill.synthesis.SkillSynthesisService;
import vip.newsclaw.skill.runtime.SkillRuntimeService;
import vip.newsclaw.skill.runtime.model.ResolvedSkill;
import vip.newsclaw.skill.workspace.BundledSkillSyncer;
import vip.newsclaw.skill.workspace.SkillFileSyncer;
import vip.newsclaw.skill.workspace.SkillWorkspaceManager;
import vip.newsclaw.skill.workspace.bundle.SkillBundleFiles;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.skill.lifecycle.ConfirmRequiredException;
import vip.newsclaw.skill.lifecycle.LifecycleTransition;
import vip.newsclaw.skill.lifecycle.SkillCuratorJob;
import vip.newsclaw.skill.lifecycle.SkillSnapshotService;
import vip.newsclaw.skill.routine.SkillRoutineMiner;
import vip.newsclaw.skill.routine.SkillRoutineService;
import vip.newsclaw.skill.lifecycle.SkillCuratorReport;
import vip.newsclaw.skill.lifecycle.SkillCuratorReportStore;
import vip.newsclaw.skill.lifecycle.SkillLifecycleService;
import vip.newsclaw.skill.lifecycle.model.SkillSnapshotEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能管理接口
 * <p>
 * 提供技能的 CRUD、启用/禁用、按类型查询、技能摘要等能力。
 * 对应前端 SkillMarket 页面。
 *
 * @author NewsClaw Team
 */
@Tag(name = "技能管理")
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;
    private final SkillRuntimeService skillRuntimeService;
    private final SkillPackageResolver skillPackageResolver;
    private final SkillWorkspaceManager workspaceManager;
    private final BundledSkillSyncer bundledSkillSyncer;
    private final SkillFileSyncer skillFileSyncer;
    private final SkillSynthesisService synthesisService;
    private final SkillDependencyChecker dependencyChecker;
    private final SkillLessonsService lessonsService;
    private final AgentSkillBindingMapper agentSkillBindingMapper;
    private final AgentService agentService;
    private final AgentBindingService agentBindingService;
    private final vip.newsclaw.skill.mcp.McpSkillBridge mcpSkillBridge;
    private final vip.newsclaw.skill.acp.AcpSkillBridge acpSkillBridge;
    private final SkillLifecycleService skillLifecycleService;
    private final SkillCuratorJob skillCuratorJob;
    private final SkillCuratorReportStore skillCuratorReportStore;
    private final SkillSnapshotService skillSnapshotService;
    private final SkillRoutineService skillRoutineService;
    private final SkillRoutineMiner skillRoutineMiner;
    private final SkillFileService skillFileService;

    @Operation(summary = "获取技能分页列表（RFC-042 §2.1）")
    @GetMapping
    @RequireWorkspaceRole("member")
    public R<IPage<SkillEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String skillType,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String scanStatus,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String runtime,
            @RequestParam(required = false) String lifecycleState,
            @RequestParam(required = false) Long agentId) {
        Set<Long> pinnedSkillIds = agentId != null ? agentBindingService.getBoundSkillIds(agentId) : Set.of();
        if (pinnedSkillIds == null) pinnedSkillIds = Set.of();
        IPage<SkillEntity> dbPage = skillService.pageSkills(
                page, size, keyword, skillType, enabled, scanStatus, sort, source, runtime,
                pinnedSkillIds, workspaceId, lifecycleState);
        // Virtual MCP/ACP skills mirror live servers and carry no lifecycle
        // state — exclude them whenever the caller filters by lifecycleState
        // (stale / archived / active), otherwise they leak into every tab.
        List<SkillEntity> virtualSkills = (lifecycleState != null && !lifecycleState.isBlank())
                ? List.of()
                : visibleVirtualSkills(
                        workspaceId, keyword, skillType, enabled, scanStatus, sort, source, runtime);
        if (!virtualSkills.isEmpty()) {
            VirtualPageMergeResult merged = mergeVirtualTailPageRecords(
                    dbPage.getRecords(), virtualSkills, dbPage.getTotal(), page, size);
            dbPage.setRecords(merged.records());
            dbPage.setTotal(merged.total());
        }
        return R.ok(dbPage);
    }

    @Operation(summary = "获取各类型技能计数（tab 徽章用）")
    @GetMapping("/counts")
    @RequireWorkspaceRole("member")
    public R<Map<String, Long>> counts(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        Map<String, Long> result = skillService.countByType(workspaceId);
        Set<String> realNames = realSkillNames(workspaceId);
        // RFC-090 §3.2 — virtual MCP-derived skills aren't in mate_skill,
        // so countByType() misses them. Fold in the live count so the
        // "MCP" and "all" tab badges match what the list endpoint shows.
        try {
            long virtualMcp = countUnshadowedVirtualSkills(
                    mcpSkillBridge.listMcpDerivedSkillEntities(), realNames);
            if (virtualMcp > 0) {
                result.merge("mcp", virtualMcp, Long::sum);
                result.merge("all", virtualMcp, Long::sum);
            }
        } catch (Exception ignored) {
            // Bridge failure must not break the badge fetch.
        }
        try {
            long virtualAcp = countUnshadowedVirtualSkills(
                    listAcpVirtualSkills(workspaceId), realNames);
            if (virtualAcp > 0) {
                result.merge("acp", virtualAcp, Long::sum);
                result.merge("all", virtualAcp, Long::sum);
            }
        } catch (Exception ignored) {
            // Same defensive stance as the MCP bridge above.
        }
        return R.ok(result);
    }

    /**
     * Real skill rows own their slug. Same-name MCP/ACP virtual rows are
     * shadowed even when the real row is disabled, matching the runtime status
     * view and avoiding duplicate cards for the same capability. The comparison
     * is name-only because {@code mate_skill.name} is the unique skill slug.
     */
    static List<SkillEntity> filterShadowedVirtualSkills(List<SkillEntity> virtualSkills,
                                                         Set<String> realSkillNames) {
        if (virtualSkills == null || virtualSkills.isEmpty()) return List.of();
        Set<String> realNames = realSkillNames == null ? Set.of() : realSkillNames;
        return virtualSkills.stream()
                .filter(s -> s != null && !realNames.contains(s.getName()))
                .toList();
    }

    static long countUnshadowedVirtualSkills(List<SkillEntity> virtualSkills,
                                             Set<String> realSkillNames) {
        return filterShadowedVirtualSkills(virtualSkills, realSkillNames).size();
    }

    /** Keep only enabled rows — gates virtual skills into enabled-only endpoints. */
    static List<SkillEntity> enabledOnly(List<SkillEntity> skills) {
        if (skills == null || skills.isEmpty()) return List.of();
        return skills.stream()
                .filter(s -> s != null && Boolean.TRUE.equals(s.getEnabled()))
                .toList();
    }

    /**
     * Keep MyBatis-Plus as the source of truth for DB pagination and append
     * live virtual ACP/MCP rows after the DB rows. This produces one stable
     * combined sequence:
     * <pre>
     *   [all DB rows sorted by SQL] + [unshadowed live virtual rows]
     * </pre>
     *
     * The tail ordering avoids shifting every DB page whenever a runtime ACP
     * endpoint appears, and it keeps {@code total} consistent across all pages.
     */
    static VirtualPageMergeResult mergeVirtualTailPageRecords(List<SkillEntity> dbRecords,
                                                              List<SkillEntity> virtualSkills,
                                                              long dbTotal,
                                                              int page,
                                                              int size) {
        List<SkillEntity> records = new ArrayList<>(dbRecords == null ? List.of() : dbRecords);
        List<SkillEntity> virtualRows = virtualSkills == null ? List.of() : virtualSkills;
        long normalizedDbTotal = Math.max(dbTotal, 0);
        long total = normalizedDbTotal + virtualRows.size();
        if (virtualRows.isEmpty()) {
            return new VirtualPageMergeResult(records, total);
        }

        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        long startInclusive = (long) (safePage - 1) * safeSize;
        long endExclusive = startInclusive + safeSize;
        if (endExclusive <= normalizedDbTotal || records.size() >= safeSize) {
            return new VirtualPageMergeResult(records, total);
        }

        long virtualStart = Math.max(0, startInclusive - normalizedDbTotal);
        int remainingSlots = safeSize - records.size();
        for (long i = virtualStart; i < virtualRows.size() && remainingSlots > 0; i++) {
            records.add(virtualRows.get((int) i));
            remainingSlots--;
        }
        return new VirtualPageMergeResult(records, total);
    }

    record VirtualPageMergeResult(List<SkillEntity> records, long total) {}

    private List<SkillEntity> visibleVirtualSkills(Long workspaceId,
                                                   String keyword,
                                                   String skillType,
                                                   Boolean enabled,
                                                   String scanStatus,
                                                   String sort,
                                                   String source,
                                                   String runtime) {
        String effectiveSource = source != null && !source.isBlank() ? source : skillType;
        boolean includeMcpVirtuals = isAllSkillType(effectiveSource) || "mcp".equalsIgnoreCase(effectiveSource);
        boolean includeAcpVirtuals = isAllSkillType(effectiveSource) || "acp".equalsIgnoreCase(effectiveSource);
        if (!includeMcpVirtuals && !includeAcpVirtuals) return List.of();

        Set<String> realNames = realSkillNames(workspaceId);
        List<SkillEntity> result = new ArrayList<>();
        if (includeMcpVirtuals) {
            try {
                result.addAll(filterVirtualSkills(mcpSkillBridge.listMcpDerivedSkillEntities(),
                        realNames, keyword, enabled, scanStatus, runtime));
            } catch (Exception ignored) {
                // Bridge failure must not break the Skills page.
            }
        }
        if (includeAcpVirtuals) {
            try {
                result.addAll(filterVirtualSkills(listAcpVirtualSkills(workspaceId),
                        realNames, keyword, enabled, scanStatus, runtime));
            } catch (Exception ignored) {
                // Bridge failure must not break the Skills page.
            }
        }
        return SkillCatalogSorter.sortEntities(result, SkillCatalogSort.parse(sort));
    }

    private static List<SkillEntity> filterVirtualSkills(List<SkillEntity> virtualSkills,
                                                         Set<String> realNames,
                                                         String keyword,
                                                         Boolean enabled,
                                                         String scanStatus,
                                                         String runtime) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        String normalizedScan = scanStatus == null ? "" : scanStatus.trim().toUpperCase();
        return filterShadowedVirtualSkills(virtualSkills, realNames).stream()
                .filter(s -> kw.isEmpty() || containsIgnoreCase(s.getName(), kw)
                        || containsIgnoreCase(s.getDescription(), kw)
                        || containsIgnoreCase(s.getTags(), kw))
                .filter(s -> enabled == null || enabled.equals(s.getEnabled()))
                .filter(s -> normalizedScan.isEmpty()
                        || normalizedScan.equalsIgnoreCase(s.getSecurityScanStatus()))
                .filter(s -> SkillCatalogSorter.runtimeMatches(s, runtime))
                .toList();
    }

    private static boolean isAllSkillType(String skillType) {
        return skillType == null || skillType.isBlank() || "all".equalsIgnoreCase(skillType);
    }

    private static boolean containsIgnoreCase(String value, String lowerCaseNeedle) {
        return value != null && value.toLowerCase().contains(lowerCaseNeedle);
    }

    /**
     * Names of every real {@code mate_skill} row visible in {@code
     * workspaceId} (builtin + workspace-owned). Used to shadow same-named
     * MCP/ACP virtual skills so the catalog never shows two cards for one
     * capability.
     */
    private Set<String> realSkillNames(Long workspaceId) {
        return skillService.listSkills(workspaceId).stream()
                .map(SkillEntity::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Operation(summary = "重新扫描单个技能（RFC-042 §2.3.4）")
    @PostMapping("/{id}/rescan")
    @RequireWorkspaceRole("admin")
    public R<SkillEntity> rescan(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        rejectVirtualSkillMutation(id);
        verifyResourceWorkspace(skillService.getSkill(id), workspaceId);
        return R.ok(skillService.rescanSecurity(id));
    }

    @Operation(summary = "Re-sync this skill's bundle files from DB → local workspace cache",
            description = "Use after an out-of-band scripts/ change or to recover a missing local cache " +
                    "in a multi-instance deployment. Pulls every mate_skill_file row owned by the skill " +
                    "down to disk; if no rows exist yet but local files do, ingests them into the canonical store.")
    @PostMapping("/{id}/sync-files")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> syncFiles(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        rejectVirtualSkillMutation(id);
        SkillEntity skill = skillService.getSkill(id);
        verifyResourceWorkspace(skill, workspaceId);
        var report = skillFileSyncer.syncOne(skill);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillId", id);
        body.put("name", skill.getName());
        body.put("filesMaterialized", report.filesMaterialized());
        body.put("filesAlreadyCurrent", report.filesAlreadyCurrent());
        body.put("filesBackfilledFromDisk", report.filesBackfilledFromDisk());
        body.put("backfilledFromDisk", report.didBackfillFromDisk());
        return R.ok(body);
    }

    @Operation(summary = "Re-sync every skill's bundle files (admin)",
            description = "Bulk variant of /sync-files; primarily for ops debugging when you suspect " +
                    "the local workspace is out of sync with the canonical store.")
    @PostMapping("/sync-files")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> syncAllFiles(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        var report = skillFileSyncer.syncAll(workspaceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillsConsidered", report.skillsConsidered());
        body.put("skillsBackfilled", report.skillsBackfilled());
        body.put("filesMaterialized", report.filesMaterialized());
        body.put("filesAlreadyCurrent", report.filesAlreadyCurrent());
        body.put("filesBackfilledFromDisk", report.filesBackfilledFromDisk());
        return R.ok(body);
    }

    // ==================== Bundle files (scripts/ + references/) ====================

    /** Per-file content ceiling for the admin editor — matches the installer's per-file bound. */
    private static final int MAX_BUNDLE_FILE_CHARS = 1_000_000;

    @Operation(summary = "List a skill's bundle files (scripts/ + references/), without content")
    @GetMapping("/{id}/files")
    @RequireWorkspaceRole("member")
    public R<List<Map<String, Object>>> listBundleFiles(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        // Virtual MCP/ACP skills mirror live servers and own no bundle files.
        if (vip.newsclaw.skill.mcp.McpSkillBridge.isVirtualMcpSkillId(id)
                || vip.newsclaw.skill.acp.AcpSkillBridge.isVirtualAcpSkillId(id)) {
            return R.ok(List.of());
        }
        SkillEntity skill = skillService.getSkill(id);
        verifyResourceWorkspace(skill, workspaceId);
        List<SkillFileEntity> rows = skillFileService.listBySkillId(id);
        if (rows.isEmpty()) {
            // Self-heal: ingest pre-canonical on-disk files into the DB store
            // so legacy directory-based skills list their files too.
            skillFileSyncer.syncOne(skill);
            rows = skillFileService.listBySkillId(id);
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        rows.stream()
                .sorted(java.util.Comparator.comparing(SkillFileEntity::getFilePath,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .forEach(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", row.getFilePath());
                    item.put("size", row.getContentSize());
                    item.put("sha256", row.getSha256());
                    item.put("updateTime", row.getUpdateTime());
                    out.add(item);
                });
        return R.ok(out);
    }

    @Operation(summary = "Read one bundle file's content")
    @GetMapping("/{id}/files/content")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> getBundleFileContent(@PathVariable Long id,
            @RequestParam String path,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        SkillEntity skill = skillService.getSkill(id);
        verifyResourceWorkspace(skill, workspaceId);
        String normalized = normalizeBundlePath(path);
        if (normalized == null) {
            return R.fail("Invalid file path: " + path);
        }
        SkillFileEntity row = skillFileService.getFile(id, normalized);
        if (row == null) {
            return R.fail("File not found: " + normalized);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", row.getFilePath());
        body.put("content", row.getContent() == null ? "" : row.getContent());
        body.put("size", row.getContentSize());
        body.put("sha256", row.getSha256());
        body.put("updateTime", row.getUpdateTime());
        return R.ok(body);
    }

    @Operation(summary = "Create or update one bundle file",
            description = "Writes the canonical mate_skill_file row, materializes the workspace cache, " +
                    "and re-resolves the skill so the runtime file tree updates immediately.")
    @PutMapping("/{id}/files/content")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> putBundleFileContent(@PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        rejectVirtualSkillMutation(id);
        SkillEntity skill = skillService.getSkill(id);
        verifyResourceWorkspace(skill, workspaceId);
        if (body == null) return R.fail("request body is required");
        if (Boolean.TRUE.equals(skill.getBuiltin())) {
            return R.fail("Builtin skill files are read-only — they are restored from the shipped bundle on upgrade.");
        }
        String normalized = normalizeBundlePath(body.get("path"));
        if (normalized == null) {
            return R.fail("Invalid file path — must be under scripts/, references/ or templates/, no '..'.");
        }
        String content = body.get("content");
        if (content == null) {
            return R.fail("content is required (use the delete endpoint to remove a file).");
        }
        if (content.length() > MAX_BUNDLE_FILE_CHARS) {
            return R.fail("Content too large (" + content.length() + " chars, max " + MAX_BUNDLE_FILE_CHARS + ").");
        }

        SkillFileEntity row = skillFileService.upsertFile(id, normalized, content);
        try {
            workspaceManager.writeWorkspaceFile(skill.getName(), normalized, content, skill.getWorkspaceId());
        } catch (Exception e) {
            // Canonical store is updated; the syncer heals the cache later.
        }
        skillRuntimeService.rescanSingle(skill);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", row.getFilePath());
        out.put("size", row.getContentSize());
        out.put("sha256", row.getSha256());
        out.put("updateTime", row.getUpdateTime());
        return R.ok(out);
    }

    @Operation(summary = "Delete one bundle file")
    @DeleteMapping("/{id}/files")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> deleteBundleFile(@PathVariable Long id,
            @RequestParam String path,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        rejectVirtualSkillMutation(id);
        SkillEntity skill = skillService.getSkill(id);
        verifyResourceWorkspace(skill, workspaceId);
        if (Boolean.TRUE.equals(skill.getBuiltin())) {
            return R.fail("Builtin skill files are read-only — they are restored from the shipped bundle on upgrade.");
        }
        String normalized = normalizeBundlePath(path);
        if (normalized == null) {
            return R.fail("Invalid file path: " + path);
        }
        boolean removed = skillFileService.deleteFile(id, normalized);
        try {
            workspaceManager.deleteWorkspaceFile(skill.getName(), normalized, skill.getWorkspaceId());
        } catch (Exception e) {
            // Cache cleanup is best-effort; the canonical row is gone.
        }
        skillRuntimeService.rescanSingle(skill);
        return R.ok(Map.of("path", normalized, "removed", removed));
    }

    /**
     * Normalize a bundle-relative path and enforce the same envelope the
     * store and workspace cache use: forward slashes, must sit under a
     * DB-persisted bucket ({@code scripts/}, {@code references/} or
     * {@code templates/}), no traversal, no absolute paths, and a
     * non-empty file name.
     *
     * @return normalized path, or {@code null} when rejected
     */
    static String normalizeBundlePath(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.strip().replace('\\', '/');
        if (p.startsWith("/") || p.contains("..") || p.contains("//") || p.endsWith("/")) return null;
        if (!SkillBundleFiles.isDbEligible(p)) return null;
        int slash = p.indexOf('/');
        if (slash == p.length() - 1) return null;
        return p;
    }

    /**
     * Mutation paths refuse virtual MCP/ACP skill ids upfront. The bridge
     * synthesizes those rows on the fly from the upstream connection
     * config; persisting an update against {@code mate_skill} would
     * either silently no-op (no row to update) or — as users have hit —
     * throw "技能不存在" because the lookup precedes the update. Sending
     * a clear 4xx with a redirect hint is the right shape: the user
     * wants the icon / display name / etc. to stick, and the only place
     * those fields persist for an MCP entry is the MCP connection page.
     */
    private void rejectVirtualSkillMutation(Long id) {
        if (vip.newsclaw.skill.mcp.McpSkillBridge.isVirtualMcpSkillId(id)
                || vip.newsclaw.skill.acp.AcpSkillBridge.isVirtualAcpSkillId(id)) {
            throw new vip.newsclaw.exception.NewsClawException(
                    "err.skill.virtual_readonly",
                    "MCP/ACP 衍生技能不可在此编辑——请到 Settings ▸ MCP/ACP 连接页修改");
        }
    }

    /**
     * Reject access to a skill that the request's workspace doesn't own.
     * Builtin skills are global and exempt — every workspace may read and
     * (where role permits) toggle them. The interceptor already verified
     * the caller's role inside {@code workspaceId}; this guard closes the
     * remaining gap where a member of workspace B targets a skill id that
     * actually belongs to workspace A.
     */
    private void verifyResourceWorkspace(SkillEntity skill, Long headerWorkspaceId) {
        if (skill == null) {
            throw new vip.newsclaw.exception.NewsClawException("err.skill.not_found", 404,
                    "Skill not found");
        }
        if (Boolean.TRUE.equals(skill.getBuiltin())) {
            return;
        }
        long requested = effectiveWorkspace(headerWorkspaceId);
        long owner = skill.getWorkspaceId() != null
                ? skill.getWorkspaceId() : SkillService.DEFAULT_WORKSPACE_ID;
        if (owner != requested) {
            throw new vip.newsclaw.exception.NewsClawException("err.common.wrong_workspace", 403,
                    "Skill " + skill.getId() + " does not belong to the current workspace");
        }
    }

    @Operation(summary = "获取已启用技能列表")
    @GetMapping("/enabled")
    @RequireWorkspaceRole("member")
    public R<List<SkillEntity>> listEnabled(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        // Mirror the merging the paginated /skills endpoint does so the agent
        // edit picker (which calls this endpoint) sees MCP- and ACP-derived
        // virtual skills alongside the persisted ones. The shadow base must
        // include all real skill names — including disabled ones — so a
        // disabled real skill correctly suppresses its same-named virtual
        // twin, matching /skills and /counts.
        // The bridges surface disabled MCP/ACP servers too (so the Skills
        // page can show a toggled-off card); this endpoint is enabled-only,
        // so the virtual rows are filtered to enabled before merging.
        List<SkillEntity> result = new ArrayList<>(skillService.listEnabledSkills(workspaceId));
        Set<String> realNames = realSkillNames(workspaceId);

        try {
            result.addAll(enabledOnly(filterShadowedVirtualSkills(
                    mcpSkillBridge.listMcpDerivedSkillEntities(), realNames)));
        } catch (Exception e) {
            // Bridge failure must not 500 the picker — same defensive stance as /counts.
        }
        try {
            result.addAll(enabledOnly(filterShadowedVirtualSkills(
                    listAcpVirtualSkills(workspaceId), realNames)));
        } catch (Exception e) {
            // Bridge failure must not 500 the picker — same defensive stance as /counts.
        }
        return R.ok(result);
    }

    /** Null workspace means the legacy/default view; keep the bridge's
     * unscoped overload for that mode so direct callers and old installations
     * continue to see all ACP virtual skills. */
    private List<SkillEntity> listAcpVirtualSkills(Long workspaceId) {
        // Keep direct/unit callers' legacy no-arg behavior, while an actual
        // HTTP request with an omitted header still receives the default
        // workspace-scoped view enforced by WorkspaceAccessInterceptor.
        if (workspaceId == null
                && !(org.springframework.web.context.request.RequestContextHolder
                .getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes)) {
            return acpSkillBridge.listAcpDerivedSkillEntities();
        }
        return acpSkillBridge.listAcpDerivedSkillEntities(effectiveWorkspace(workspaceId));
    }

    private static long effectiveWorkspace(Long workspaceId) {
        return workspaceId == null || workspaceId <= 0
                ? SkillService.DEFAULT_WORKSPACE_ID : workspaceId;
    }

    @Operation(summary = "按类型获取技能列表")
    @GetMapping("/type/{skillType}")
    @RequireWorkspaceRole("member")
    public R<List<SkillEntity>> listByType(@PathVariable String skillType,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillService.listSkillsByType(skillType, workspaceId));
    }

    @Operation(summary = "获取已启用技能摘要（按类型分组）")
    @GetMapping("/summary")
    @RequireWorkspaceRole("member")
    public R<Map<String, List<String>>> summary(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillService.getEnabledSkillSummary(workspaceId));
    }

    @Operation(summary = "获取技能详情")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<SkillEntity> get(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        // RFC-090 §3.2 — virtual MCP-derived skills synthesize a row
        // on demand from the live MCP server entity.
        if (vip.newsclaw.skill.mcp.McpSkillBridge.isVirtualMcpSkillId(id)) {
            List<SkillEntity> virt = mcpSkillBridge.listMcpDerivedSkillEntities();
            return virt.stream()
                    .filter(s -> id.equals(s.getId()))
                    .findFirst()
                    .map(R::ok)
                    .orElse(R.fail("MCP-derived skill not found: " + id));
        }
        // RFC-090 §3.2 (parallel) — same path for ACP-derived virtual skills.
        if (vip.newsclaw.skill.acp.AcpSkillBridge.isVirtualAcpSkillId(id)) {
            SkillEntity ent = acpSkillBridge.findEntityById(id, effectiveWorkspace(workspaceId));
            return ent != null ? R.ok(ent) : R.fail("ACP-derived skill not found: " + id);
        }
        SkillEntity skill = skillService.getSkill(id);
        verifyResourceWorkspace(skill, workspaceId);
        // Read-time store reconciliation: the workspace SKILL.md may have
        // been edited out-of-band (agent shell tools in a chat session)
        // with no refresh in between. One file read + hash compare in the
        // steady state; when the file side actually changed, the content
        // is ingested and a single-skill rescan re-projects manifest
        // columns and drops stale runtime caches — so the detail view
        // always shows what the runtime executes.
        try {
            if (skillPackageResolver.reconcileEntityContent(skill)) {
                skillRuntimeService.rescanSingle(skill);
            }
        } catch (Exception ignored) {
            // A reconcile failure must not break the detail view.
        }
        return R.ok(skill);
    }

    @Operation(summary = "创建技能")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<SkillEntity> create(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody SkillEntity skill) {
        if (skill == null) return R.fail(400, "request body is required");
        // Always stamp the owning workspace from the request context — never
        // trust a workspaceId in the request body.
        skill.setWorkspaceId(workspaceId != null
                ? workspaceId : SkillService.DEFAULT_WORKSPACE_ID);
        return R.ok(skillService.createSkill(skill));
    }

    @Operation(summary = "更新技能")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<SkillEntity> update(@PathVariable Long id, @RequestBody SkillEntity skill,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        if (skill == null) return R.fail(400, "request body is required");
        rejectVirtualSkillMutation(id);
        verifyResourceWorkspace(skillService.getSkill(id), workspaceId);
        skill.setId(id);
        return R.ok(skillService.updateSkill(skill));
    }

    /**
     * RFC-090 §14.5 — admin-only hard delete: physical row removal +
     * workspace purge. UI surfaces this as "permanently delete" and
     * confirms with a destructive warning. The routine user-facing
     * "remove" button on the skill card calls
     * {@code DELETE /skills/install/{name}} instead, which goes through
     * {@link vip.newsclaw.skill.installer.SkillInstaller#uninstall} for the
     * recoverable logical-delete + archive path.
     */
    @Operation(summary = "硬删除技能 (admin only — 物理删除 + 工作区清空)")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> delete(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        rejectVirtualSkillMutation(id);
        verifyResourceWorkspace(skillService.getSkill(id), workspaceId);
        skillService.hardDeleteSkill(id);
        return R.ok();
    }

    @Operation(summary = "启用/禁用技能")
    @PutMapping("/{id}/toggle")
    @RequireWorkspaceRole("admin")
    public R<SkillEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication authentication) {
        // A virtual MCP skill mirrors an MCP server — toggling it enables /
        // disables that server, keeping the Skills page and Settings ▸ MCP
        // Connections in sync. ACP virtual skills have no such mapping and
        // stay read-only via rejectVirtualSkillMutation below.
        if (vip.newsclaw.skill.mcp.McpSkillBridge.isVirtualMcpSkillId(id)) {
            boolean globalAdmin = authentication != null && authentication.getAuthorities() != null
                    && authentication.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            if (!globalAdmin) return R.fail(403, "Global administrator role required for MCP changes");
            return R.ok(mcpSkillBridge.toggleVirtualSkill(id, enabled));
        }
        rejectVirtualSkillMutation(id);
        verifyResourceWorkspace(skillService.getSkill(id), workspaceId);
        return R.ok(skillService.toggleSkill(id, enabled));
    }

    @Operation(summary = "预览技能 Prompt 增强效果（调试用，与 Agent 真实运行时一致）")
    @GetMapping("/prompt-preview")
    @vip.newsclaw.workspace.core.annotation.RequireGlobalAdmin
    public R<Map<String, Object>> promptPreview() {
        String prompt = skillRuntimeService.buildSkillPromptEnhancement();
        return R.ok(Map.of(
                "actualLength", prompt.length(),
                "estimatedTokens", prompt.length() / 3,
                "prompt", prompt
        ));
    }

    // ==================== Runtime API ====================

    /** Source-compatible helper for non-HTTP callers; HTTP uses the authenticated overload below. */
    public R<SkillEntity> toggle(Long id, boolean enabled, Long workspaceId) {
        if (vip.newsclaw.skill.mcp.McpSkillBridge.isVirtualMcpSkillId(id)) {
            return R.ok(mcpSkillBridge.toggleVirtualSkill(id, enabled));
        }
        return toggle(id, enabled, workspaceId, null);
    }

    @Operation(summary = "获取 active skills 运行时视图")
    @GetMapping("/runtime/active")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> getActiveSkills(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        List<ResolvedSkill> skills = skillRuntimeService.getActiveSkills(
                workspaceId == null ? SkillService.DEFAULT_WORKSPACE_ID : workspaceId);
        return R.ok(Map.of("count", skills.size(), "skills", skills));
    }

    @Operation(summary = "获取所有技能的运行时解析状态（管理页面使用）")
    @GetMapping("/runtime/status")
    @RequireWorkspaceRole("admin")
    public R<List<ResolvedSkill>> getRuntimeStatus(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillRuntimeService.resolveAllSkillsStatus(
                workspaceId == null ? SkillService.DEFAULT_WORKSPACE_ID : workspaceId));
    }

    @Operation(summary = "刷新 active skills 缓存，resync=true 时同步内置技能到 workspace")
    @PostMapping("/runtime/refresh")
    @vip.newsclaw.workspace.core.annotation.RequireGlobalAdmin
    public R<Map<String, Object>> refreshRuntime(
            @RequestParam(defaultValue = "false") boolean resync) {
        List<String> resynced = List.of();
        if (resync) {
            resynced = bundledSkillSyncer.sync();
        }
        List<ResolvedSkill> skills = skillRuntimeService.refreshActiveSkills();
        return R.ok(Map.of(
                "count", skills.size(),
                "message", resync ? "Active skills refreshed with workspace resync" : "Active skills refreshed",
                "resynced", resynced));
    }

    // ==================== Requirements API (RFC-090 §7) ====================

    /**
     * RFC-090 §7 — pre-flight check: returns the per-requirement status
     * for the skill so the install dialog and the detail drawer can render
     * "✓ ffmpeg detected / ✗ groq_key missing" rows.
     *
     * <p>Calls the same {@link SkillDependencyChecker} the runtime uses,
     * so the UI never disagrees with the runtime gate.
     */
    @Operation(summary = "Pre-flight requirement statuses for a skill (RFC-090)")
    @GetMapping("/{id}/requirements")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> requirements(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifySkillIdWorkspace(id, workspaceId);
        Long effectiveWorkspaceId = workspaceId == null ? SkillService.DEFAULT_WORKSPACE_ID : workspaceId;
        ResolvedSkill resolved = skillRuntimeService.resolveAllSkillsStatus(effectiveWorkspaceId).stream()
                .filter(r -> r != null && id.equals(r.getId()))
                .findFirst()
                .orElse(null);
        if (resolved == null) {
            return R.fail("Skill not found: " + id);
        }
        SkillManifest manifest = resolved.getManifest();
        if (manifest == null) {
            // Legacy skill — fall back to dependency summary already on the resolved view.
            return R.ok(Map.of(
                    "allMet", resolved.isDependencyReady(),
                    "statuses", List.of(),
                    "summary", resolved.getDependencySummary() != null ? resolved.getDependencySummary() : ""
            ));
        }

        List<Map<String, Object>> statuses = new ArrayList<>();
        boolean allMet = true;
        for (SkillManifest.RequirementDef req : manifest.getRequires()) {
            SkillDependencyChecker.RequirementStatus st = dependencyChecker.checkRequirement(req);
            boolean satisfied = st == SkillDependencyChecker.RequirementStatus.SATISFIED;
            if (!satisfied && !req.isOptional()) allMet = false;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", req.getKey());
            row.put("type", req.getType());
            row.put("description", req.getDescription());
            row.put("optional", req.isOptional());
            row.put("status", st.name());
            row.put("satisfied", satisfied);
            row.put("installCommands", req.getInstall());
            statuses.add(row);
        }
        return R.ok(Map.of(
                "allMet", allMet,
                "statuses", statuses,
                "featureStatuses", resolved.getFeatureStatuses(),
                "activeFeatures", resolved.getActiveFeatures()
        ));
    }

    // ==================== Reverse lookup (RFC-090 §7) ====================

    /**
     * RFC-090 §7 / §14.2 — list agents (employees) for which this
     * skill is reachable.
     *
     * <p>Two coverage paths, both reflected in the response:
     * <ol>
     *   <li><b>Explicit</b> — there's a {@code mate_agent_skill} row
     *       with this skill id and {@code enabled=true}.</li>
     *   <li><b>Implicit</b> — the agent has no explicit skill binding
     *       at all ({@code AgentBindingService.getBoundSkillIds}
     *       returns null), which the three-state contract treats as
     *       "use every globally-enabled skill". Most users never wire
     *       explicit bindings, so without this branch the count was
     *       always zero even for skills clearly visible to the LLM.</li>
     * </ol>
     *
     * <p>Each row carries {@code binding: "explicit" | "implicit"} so
     * the UI can label the relationship.
     */
    @Operation(summary = "List agents that can use this skill (RFC-090 §14.2)")
    @GetMapping("/{id}/employees")
    @RequireWorkspaceRole("member")
    public R<List<Map<String, Object>>> employees(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifySkillIdWorkspace(id, workspaceId);
        long effectiveWorkspaceId = workspaceId == null ? SkillService.DEFAULT_WORKSPACE_ID : workspaceId;
        // Explicit bindings: agent_skill rows pointing to this skill.
        List<AgentSkillBinding> explicitBindings = agentSkillBindingMapper.selectList(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getSkillId, id)
                        .eq(AgentSkillBinding::getEnabled, true));
        java.util.Set<Long> explicitAgentIds = new java.util.LinkedHashSet<>();
        for (AgentSkillBinding b : explicitBindings) explicitAgentIds.add(b.getAgentId());

        // Implicit bindings: agents whose getBoundSkillIds() == null
        // (no explicit row in agent_skill at all). They get every
        // globally-enabled skill, which includes this one provided the
        // skill itself is enabled.
        List<AgentEntity> allAgents = agentService.listAgentsByWorkspace(effectiveWorkspaceId);
        java.util.Set<Long> visibleAgentIds = allAgents.stream()
                .map(AgentEntity::getId).collect(java.util.stream.Collectors.toSet());
        java.util.Set<Long> implicitAgentIds = new java.util.LinkedHashSet<>();
        SkillEntity skill;
        try {
            skill = skillService.getSkill(id);
        } catch (Exception e) {
            skill = null;
        }
        boolean skillGloballyAvailable = skill != null && Boolean.TRUE.equals(skill.getEnabled());
        if (skillGloballyAvailable) {
            for (AgentEntity agent : allAgents) {
                if (explicitAgentIds.contains(agent.getId())) continue;
                java.util.Set<Long> bound = agentBindingService.getBoundSkillIds(agent.getId());
                // null → no explicit bindings → uses every enabled skill
                if (bound == null) implicitAgentIds.add(agent.getId());
            }
        }

        java.util.List<Map<String, Object>> rows = new ArrayList<>();
        // Stable order: explicit first (most "intentional" relationship),
        // then implicit, agents inside each group keep DB insert order.
        for (Long agentId : explicitAgentIds) {
            if (visibleAgentIds.contains(agentId)) appendAgentRow(rows, agentId, "explicit");
        }
        for (Long agentId : implicitAgentIds) {
            if (visibleAgentIds.contains(agentId)) appendAgentRow(rows, agentId, "implicit");
        }
        return R.ok(rows);
    }

    private void appendAgentRow(List<Map<String, Object>> rows, Long agentId, String binding) {
        try {
            AgentEntity agent = agentService.getAgent(agentId);
            if (agent == null) return;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", agent.getId());
            row.put("name", agent.getName());
            row.put("icon", agent.getIcon());
            row.put("binding", binding);
            rows.add(row);
        } catch (Exception ignored) {
            // agent may have been deleted; skip rather than fail the whole list
        }
    }

    /** Verify persisted and virtual skill ids before detail/reverse-lookup reads. */
    private void verifySkillIdWorkspace(Long id, Long workspaceId) {
        if (id == null) {
            throw new NewsClawException("err.skill.not_found", 404, "Skill id is required");
        }
        if (vip.newsclaw.skill.mcp.McpSkillBridge.isVirtualMcpSkillId(id)) return;
        if (vip.newsclaw.skill.acp.AcpSkillBridge.isVirtualAcpSkillId(id)) {
            Long effective = workspaceId == null ? SkillService.DEFAULT_WORKSPACE_ID : workspaceId;
            if (acpSkillBridge.findEntityById(id, effective) == null) {
                throw new NewsClawException("err.common.wrong_workspace", 403,
                        "Skill does not belong to this workspace");
            }
            return;
        }
        verifyResourceWorkspace(skillService.getSkill(id), workspaceId);
    }

    // ==================== Lessons API (RFC-090 §7 + §11.4) ====================

    /**
     * Read the per-skill {@code LESSONS.md} body for the detail drawer.
     * Returns {@code entries: []} when the file is missing.
     */
    @Operation(summary = "Read per-skill LESSONS.md (RFC-090 §11.4)")
    @GetMapping("/{id}/lessons")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> getLessons(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifySkillIdWorkspace(id, workspaceId);
        Long effectiveWorkspaceId = workspaceId == null ? SkillService.DEFAULT_WORKSPACE_ID : workspaceId;
        ResolvedSkill resolved = skillRuntimeService.resolveAllSkillsStatus(effectiveWorkspaceId).stream()
                .filter(r -> r != null && id.equals(r.getId()))
                .findFirst()
                .orElse(null);
        if (resolved == null) return R.fail("Skill not found: " + id);
        String body = lessonsService.readLessons(resolved);
        // entryCount = number of "## " headed sections (RFC-090 §11.4 "💡 N entries" badge).
        int entryCount = 0;
        if (body != null && !body.isBlank()) {
            int from = 0;
            while (true) {
                int idx = body.indexOf("\n## ", from);
                if (idx < 0) break;
                entryCount++;
                from = idx + 1;
            }
            // Also count a leading "## " (no preceding newline) at start of file.
            if (body.startsWith("## ")) entryCount++;
        }
        return R.ok(Map.of(
                "skillId", id,
                "skillName", resolved.getName(),
                "raw", body == null ? "" : body,
                "entryCount", entryCount
        ));
    }

    @Operation(summary = "Clear all lessons for a skill (RFC-090 §11.4)")
    @PostMapping("/{id}/lessons/clear")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> clearLessons(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifySkillIdWorkspace(id, workspaceId);
        Long effectiveWorkspaceId = workspaceId == null ? SkillService.DEFAULT_WORKSPACE_ID : workspaceId;
        ResolvedSkill resolved = skillRuntimeService.resolveAllSkillsStatus(effectiveWorkspaceId).stream()
                .filter(r -> r != null && id.equals(r.getId()))
                .findFirst()
                .orElse(null);
        if (resolved == null) return R.fail("Skill not found: " + id);
        boolean cleared = lessonsService.clearLessons(resolved);
        return R.ok(Map.of("cleared", cleared));
    }

    // ==================== Synthesis API (RFC-023) ====================

    @Operation(summary = "从对话历史合成 Skill（RFC-023）")
    @PostMapping("/synthesize-from-conversation")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> synthesizeFromConversation(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        if (body == null) return R.fail("request body is required");
        String conversationId = (String) body.get("conversationId");
        Long agentId = body.get("agentId") != null ? Long.valueOf(body.get("agentId").toString()) : null;
        if (conversationId == null || conversationId.isBlank()) {
            return R.fail("conversationId is required");
        }

        SkillSynthesisService.SynthesisResult result = synthesisService.synthesize(
                conversationId, agentId, workspaceId);

        if (result.blocked()) {
            return R.ok(Map.of(
                    "success", false,
                    "blocked", true,
                    "skillName", result.skillName() != null ? result.skillName() : "",
                    "error", result.error(),
                    "scanSummary", result.scanSummary() != null ? result.scanSummary() : ""
            ));
        }
        if (!result.success()) {
            return R.ok(Map.of("success", false, "error", result.error()));
        }
        return R.ok(Map.of(
                "success", true,
                "skillId", result.skillId(),
                "skillName", result.skillName()
        ));
    }

    // ==================== Workspace API ====================

    @Operation(summary = "将 skill 导出到工作区目录")
    @PostMapping("/{id}/export-workspace")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> exportToWorkspace(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        SkillEntity skill = skillService.getSkill(id);
        verifyResourceWorkspace(skill, workspaceId);
        var path = workspaceManager.exportToWorkspace(skill.getName(), skill.getSkillContent(), skill.getWorkspaceId());
        if (path == null) {
            return R.ok(Map.of("success", false, "message", "Failed to export workspace"));
        }
        return R.ok(Map.of("success", true, "path", path.toString()));
    }

    @Operation(summary = "获取 skill 工作区信息")
    @GetMapping("/{id}/workspace")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> getWorkspaceInfo(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        SkillEntity skill = skillService.getSkill(id);
        verifyResourceWorkspace(skill, workspaceId);
        return R.ok(workspaceManager.getWorkspaceInfo(skill.getName(), skill.getWorkspaceId()));
    }

    // ==================== Skill lifecycle & curator ====================

    @Operation(summary = "钉住/取消钉住技能（钉住的技能不会被自动归档）")
    @PostMapping("/{id}/pin")
    @RequireWorkspaceRole("admin")
    public R<SkillEntity> pin(@PathVariable Long id,
            @RequestBody(required = false) PinRequest body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        rejectVirtualSkillMutation(id);
        verifyResourceWorkspace(skillService.getSkill(id), workspaceId);
        boolean pinned = body != null && Boolean.TRUE.equals(body.pinned());
        return R.ok(skillLifecycleService.setPinned(id, pinned));
    }

    @Operation(summary = "手动归档技能")
    @PostMapping("/{id}/archive")
    @RequireWorkspaceRole("admin")
    public R<SkillEntity> archive(@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestBody(required = false) ArchiveRequest body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        rejectVirtualSkillMutation(id);
        SkillEntity skill = skillService.getSkill(id);
        verifyResourceWorkspace(skill, workspaceId);
        if (Boolean.TRUE.equals(skill.getBuiltin())) {
            throw new NewsClawException("err.skill.builtin_not_archivable", 400,
                    "Cannot archive builtin skill: " + skill.getName());
        }
        String state = skill.getLifecycleState() == null ? "active" : skill.getLifecycleState();
        if ("archived".equals(state)) {
            throw new NewsClawException("err.skill.already_archived", 409,
                    "Skill already archived: " + skill.getName());
        }
        // Bound skills are not silently archived: require an explicit
        // second-pass confirmation (force=true) so the admin sees which
        // agents lose the capability.
        if (!force) {
            List<ConfirmRequiredException.AgentRow> bound =
                    agentBindingService.enabledAgentsBoundToSkill(id);
            if (!bound.isEmpty()) {
                throw new ConfirmRequiredException("BOUND_SKILL_CONFIRM_REQUIRED",
                        "Skill is explicitly bound to " + bound.size()
                                + " agent(s); pass force=true to confirm", bound);
            }
        }
        String reason = body != null && body.reason() != null ? body.reason() : "manual:admin";
        skillLifecycleService.applyManual(skill, LifecycleTransition.TO_ARCHIVED,
                LocalDateTime.now(), reason);
        return R.ok(skillService.getSkill(id));
    }

    @Operation(summary = "恢复已归档的技能")
    @PostMapping("/{id}/restore")
    @RequireWorkspaceRole("admin")
    public R<SkillEntity> restore(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        rejectVirtualSkillMutation(id);
        verifyResourceWorkspace(skillService.getSkill(id), workspaceId);
        return R.ok(skillLifecycleService.restore(id));
    }

    @Operation(summary = "立即运行一次 curator 预览（dry-run）")
    @PostMapping("/curator/dry-run")
    @RequireWorkspaceRole("admin")
    public R<SkillCuratorReport> curatorDryRun(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillCuratorJob.dryRunNow(workspaceId));
    }

    @Operation(summary = "激活/取消激活 curator（真正归档 vs 仅预览）")
    @PostMapping("/curator/activate")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> curatorActivate(
            @RequestParam(defaultValue = "true") boolean activate,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        skillCuratorJob.activate(workspaceId, activate);
        return R.ok(skillCuratorJob.status(workspaceId));
    }

    @Operation(summary = "暂停 curator 定时扫描")
    @PostMapping("/curator/pause")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> curatorPause(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        skillCuratorJob.setPaused(workspaceId, true);
        return R.ok(skillCuratorJob.status(workspaceId));
    }

    @Operation(summary = "恢复 curator 定时扫描")
    @PostMapping("/curator/resume")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> curatorResume(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        skillCuratorJob.setPaused(workspaceId, false);
        return R.ok(skillCuratorJob.status(workspaceId));
    }

    @Operation(summary = "开启/关闭 curator 合并去重 pass")
    @PostMapping("/curator/consolidate")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> curatorConsolidate(
            @RequestParam(defaultValue = "true") boolean enabled,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        skillCuratorJob.setConsolidate(workspaceId, enabled);
        return R.ok(skillCuratorJob.status(workspaceId));
    }

    @Operation(summary = "curator 控制面状态")
    @GetMapping("/curator/status")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> curatorStatus(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillCuratorJob.status(workspaceId));
    }

    // ==================== Routine mining ====================

    @Operation(summary = "列出挖掘到的高频请求（例行事项）候选")
    @GetMapping("/routines")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> routineList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", skillRoutineService.list(status, limit, workspaceId));
        out.put("gates", skillRoutineService.gates());
        return R.ok(out);
    }

    @Operation(summary = "立即运行一次例行事项挖掘")
    @PostMapping("/routines/mine")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> routineMine(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refreshed", skillRoutineMiner.mine(workspaceId));
        return R.ok(out);
    }

    @Operation(summary = "忽略某个例行事项候选（后续挖掘不再重开）")
    @PostMapping("/routines/{id}/dismiss")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> routineDismiss(@PathVariable String id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillRoutineService.dismiss(parseRoutineId(id), workspaceId));
    }

    @Operation(summary = "重新观察一个已忽略的例行事项候选")
    @PostMapping("/routines/{id}/reopen")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> routineReopen(@PathVariable String id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillRoutineService.reopen(parseRoutineId(id), workspaceId));
    }

    @Operation(summary = "立即把例行事项候选合成为技能（跳过频次门槛）")
    @PostMapping("/routines/{id}/promote")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> routinePromote(@PathVariable String id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        try {
            return R.ok(skillRoutineService.promoteNow(parseRoutineId(id), workspaceId));
        } catch (IllegalStateException e) {
            throw new NewsClawException("err.skill.routine_already_promoted", 409, e.getMessage());
        }
    }

    /**
     * Path variables stay strings end-to-end (19-digit snowflake ids lose
     * precision as JS numbers); parse once here so a bad id is a clean 400.
     */
    private long parseRoutineId(String id) {
        try {
            return Long.parseLong(id == null ? "" : id.strip());
        } catch (NumberFormatException e) {
            throw new NewsClawException("err.skill.routine_not_found", 400, "Invalid routine id: " + id);
        }
    }

    @Operation(summary = "列出未纳入自治治理的技能")
    @GetMapping("/curator/unmanaged")
    @RequireWorkspaceRole("member")
    public R<List<Map<String, Object>>> curatorUnmanaged(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillLifecycleService.listUnmanaged(workspaceId));
    }

    @Operation(summary = "列出已纳入自治治理的技能")
    @GetMapping("/curator/managed")
    @RequireWorkspaceRole("member")
    public R<List<Map<String, Object>>> curatorManaged(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillLifecycleService.listManaged(workspaceId));
    }

    @Operation(summary = "将技能移交给自治治理（不重置闲置时钟）")
    @PostMapping("/curator/adopt")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> curatorAdopt(@RequestBody List<String> skillIds,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(setAdoptedBulk(skillIds, true, workspaceId));
    }

    @Operation(summary = "撤销移交，技能归还用户所有")
    @PostMapping("/curator/release")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> curatorRelease(@RequestBody List<String> skillIds,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(setAdoptedBulk(skillIds, false, workspaceId));
    }

    /**
     * Apply adopt/release across a batch, reporting per-skill outcomes rather
     * than failing the whole call on one bad id — a partial batch that silently
     * rolled back would leave the operator unsure which skills moved.
     */
    private Map<String, Object> setAdoptedBulk(List<String> skillIds, boolean adopt, Long workspaceId) {
        List<String> changed = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (String raw : skillIds == null ? List.<String>of() : skillIds) {
            // Ids stay strings end-to-end; parse once here so a malformed one
            // is a reported rejection rather than a framework-level failure.
            try {
                skillLifecycleService.setAdopted(
                        Long.parseLong(String.valueOf(raw).strip()), adopt, workspaceId);
                changed.add(String.valueOf(raw));
            } catch (Exception e) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(raw));
                row.put("message", e.getMessage());
                rejected.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", changed);
        out.put("rejected", rejected);
        return out;
    }

    @Operation(summary = "列出技能库还原点")
    @GetMapping("/curator/snapshots")
    @RequireWorkspaceRole("member")
    public R<List<Map<String, Object>>> curatorSnapshots(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillSnapshotService.list(workspaceId, 20));
    }

    @Operation(summary = "手动捕获一个技能库还原点")
    @PostMapping("/curator/snapshots")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> curatorSnapshotCapture(
            @RequestParam(required = false) String reason,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        SkillSnapshotEntity snapshot = skillSnapshotService.capture(
                reason == null || reason.isBlank() ? "manual" : reason, workspaceId);
        if (snapshot == null) {
            throw new NewsClawException("err.skill.snapshot_unavailable", 400,
                    "Snapshot not captured — backups are disabled or there are no skills to capture");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // Snowflake id as a string: 19 digits exceed JS Number precision.
        out.put("id", String.valueOf(snapshot.getId()));
        out.put("reason", snapshot.getReason());
        out.put("skillCount", snapshot.getSkillCount());
        return R.ok(out);
    }

    @Operation(summary = "将技能库回滚到指定还原点")
    @PostMapping("/curator/snapshots/{snapshotId}/restore")
    @RequireWorkspaceRole("admin")
    public R<Map<String, Object>> curatorSnapshotRestore(@PathVariable String snapshotId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        // Path variable stays a String end-to-end; parsing happens once here so
        // a malformed id is a 400 rather than a framework-level failure.
        long id;
        try {
            id = Long.parseLong(snapshotId.strip());
        } catch (NumberFormatException e) {
            throw new NewsClawException("err.skill.snapshot_not_found", 400,
                    "Invalid snapshot id: " + snapshotId);
        }
        try {
            return R.ok(skillSnapshotService.restore(id, workspaceId));
        } catch (IllegalArgumentException e) {
            throw new NewsClawException("err.skill.snapshot_not_found", 404, e.getMessage());
        }
    }

    @Operation(summary = "列出最近的 curator 运行报告")
    @GetMapping("/curator/reports")
    @RequireWorkspaceRole("member")
    public R<List<String>> curatorReports(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(skillCuratorReportStore.listRunIds(workspaceId, 20));
    }

    @Operation(summary = "读取某次 curator 运行报告")
    @GetMapping("/curator/reports/{runId}")
    @RequireWorkspaceRole("member")
    public R<Object> curatorReport(@PathVariable String runId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        Object report = skillCuratorReportStore.readRun(workspaceId, runId);
        if (report == null) {
            throw new NewsClawException("err.skill.curator_report_not_found", 404,
                    "Curator report not found: " + runId);
        }
        return R.ok(report);
    }

    /** Body of {@code POST /skills/{id}/pin}. */
    public record PinRequest(Boolean pinned) {}

    /** Optional body of {@code POST /skills/{id}/archive}. */
    public record ArchiveRequest(String reason) {}
}

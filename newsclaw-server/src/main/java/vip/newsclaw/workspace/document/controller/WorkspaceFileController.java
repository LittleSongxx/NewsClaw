package vip.newsclaw.workspace.document.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.HandlerMapping;
import vip.newsclaw.common.result.R;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;
import vip.newsclaw.workspace.document.WorkspaceFileService;
import vip.newsclaw.workspace.document.WorkspaceMemoryArchiveService;
import vip.newsclaw.workspace.document.model.WorkspaceFileEntity;

import java.io.IOException;
import java.util.List;

/**
 * 工作区文件管理接口
 *
 * @author NewsClaw Team
 */
@Tag(name = "工作区文件管理")
@RestController
@RequestMapping("/api/v1/agents/{agentId}/workspace")
@RequiredArgsConstructor
public class WorkspaceFileController {

    private final WorkspaceFileService workspaceFileService;
    private final WorkspaceMemoryArchiveService memoryArchiveService;

    /**
     * 列出 Agent 的所有工作区文件（不含内容）
     */
    @Operation(summary = "列出工作区文件")
    @RequireWorkspaceRole("viewer")
    @GetMapping("/files")
    public R<List<WorkspaceFileEntity>> listFiles(@PathVariable Long agentId) {
        // Config-editor surface: shared (TEAM/GLOBAL) files only. Per-owner
        // PERSONAL memory rows are never exposed or managed through this REST API.
        return R.ok(workspaceFileService.listVisibleFiles(agentId, null));
    }

    /**
     * 读取单个文件内容（支持子目录，如 memory/2026-04-03.md）
     */
    @Operation(summary = "读取工作区文件")
    @RequireWorkspaceRole("viewer")
    @GetMapping("/files/**")
    public R<WorkspaceFileEntity> getFile(@PathVariable Long agentId, HttpServletRequest request) {
        String filename = extractFilename(request);
        WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
        if (file == null) {
            return R.fail("文件不存在: " + filename);
        }
        return R.ok(file);
    }

    /**
     * 创建或更新文件（支持子目录）
     */
    @Operation(summary = "保存工作区文件")
    @RequireWorkspaceRole("member")
    @PutMapping("/files/**")
    public R<WorkspaceFileEntity> saveFile(@PathVariable Long agentId,
                                           HttpServletRequest httpRequest,
                                           @RequestBody SaveFileRequest body) {
        if (body == null) return R.fail(400, "request body is required");
        String filename = extractFilename(httpRequest);
        return R.ok(workspaceFileService.saveFile(agentId, filename, body.getContent()));
    }

    /**
     * 删除文件（支持子目录）
     */
    @Operation(summary = "删除工作区文件")
    @RequireWorkspaceRole("member")
    @DeleteMapping("/files/**")
    public R<Void> deleteFile(@PathVariable Long agentId, HttpServletRequest request) {
        String filename = extractFilename(request);
        workspaceFileService.deleteFile(agentId, filename);
        return R.ok();
    }

    /**
     * 从请求路径中提取 /files/ 之后的文件名部分（支持含 / 的子目录路径）
     */
    private String extractFilename(HttpServletRequest request) {
        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        int filesIdx = fullPath.indexOf("/workspace/files/");
        return fullPath.substring(filesIdx + "/workspace/files/".length());
    }

    /**
     * 获取启用的系统提示文件列表（有序）
     */
    @Operation(summary = "获取系统提示文件列表")
    @RequireWorkspaceRole("viewer")
    @GetMapping("/prompt-files")
    public R<List<String>> getPromptFiles(@PathVariable Long agentId) {
        return R.ok(workspaceFileService.getPromptFiles(agentId));
    }

    /**
     * 设置启用的系统提示文件列表（有序）
     */
    @Operation(summary = "设置系统提示文件列表")
    @RequireWorkspaceRole("member")
    @PutMapping("/prompt-files")
    public R<Void> setPromptFiles(@PathVariable Long agentId,
                                   @RequestBody PromptFilesRequest request) {
        workspaceFileService.setPromptFiles(agentId, request == null ? List.of() : request.getFiles());
        return R.ok();
    }

    // ==================== Per-owner PERSONAL memory (admin read-only) ====================

    /**
     * List every owner's PERSONAL memory rows (metadata only). These rows are
     * written by agents during conversations and are scoped to a single end
     * user, so they never appear in the shared file list above. Admin-gated:
     * the listing exposes which subjects (owner keys) hold private memory.
     */
    @Operation(summary = "列出各用户的私有记忆文件（仅元数据）")
    @RequireWorkspaceRole("admin")
    @GetMapping("/memory/personal-files")
    public R<List<WorkspaceFileEntity>> listPersonalFiles(@PathVariable Long agentId) {
        return R.ok(workspaceFileService.listPersonalFiles(agentId));
    }

    /**
     * Read one owner's PERSONAL memory file content. Query params (not path
     * segments) because both the filename ({@code memory/2026-06-02.md}) and
     * the owner key ({@code feishu:ou_xxx}) contain characters that clash with
     * path mapping.
     */
    @Operation(summary = "读取单个用户私有记忆文件内容")
    @RequireWorkspaceRole("admin")
    @GetMapping("/memory/personal-file")
    public R<WorkspaceFileEntity> getPersonalFile(@PathVariable Long agentId,
                                                  @RequestParam String filename,
                                                  @RequestParam String ownerKey) {
        WorkspaceFileEntity file = workspaceFileService.getMemoryFile(agentId, filename, ownerKey);
        if (file == null) {
            return R.fail("文件不存在: " + filename);
        }
        return R.ok(file);
    }

    /** List the authenticated user's PERSONAL memory rows only. */
    @Operation(summary = "列出当前用户的私有记忆文件")
    @RequireWorkspaceRole("viewer")
    @GetMapping("/memory/my-files")
    public R<List<WorkspaceFileEntity>> listMyMemoryFiles(
            @PathVariable Long agentId, Authentication authentication) {
        return R.ok(workspaceFileService.listPersonalFiles(agentId, currentWebOwner(authentication)));
    }

    /** Read one authenticated user's PERSONAL memory file. */
    @Operation(summary = "读取当前用户的私有记忆文件")
    @RequireWorkspaceRole("viewer")
    @GetMapping("/memory/my-file")
    public R<WorkspaceFileEntity> getMyMemoryFile(
            @PathVariable Long agentId,
            @RequestParam String filename,
            Authentication authentication) {
        if (!isMemoryManagedFilename(filename)) {
            throw new NewsClawException(400, "Unsupported personal memory file: " + filename);
        }
        WorkspaceFileEntity file = workspaceFileService.getMemoryFile(
                agentId, filename, currentWebOwner(authentication));
        if (file == null) return R.fail("文件不存在: " + filename);
        return R.ok(file);
    }

    /** Update the authenticated user's PERSONAL memory file (used for a file preamble). */
    @Operation(summary = "保存当前用户的私有记忆文件")
    @RequireWorkspaceRole("member")
    @PutMapping("/memory/my-file")
    public R<WorkspaceFileEntity> saveMyMemoryFile(
            @PathVariable Long agentId,
            @RequestParam String filename,
            @RequestBody SaveFileRequest body,
            Authentication authentication) {
        if (!isMemoryManagedFilename(filename)) {
            throw new NewsClawException(400, "Unsupported personal memory file: " + filename);
        }
        return R.ok(workspaceFileService.saveMemoryFile(
                agentId, filename, body == null ? null : body.getContent(), currentWebOwner(authentication)));
    }

    // ==================== Memory snapshot export / import ====================

    /**
     * Build a ZIP snapshot of the agent's memory files for download.
     * Viewers can take backups; modifying the snapshot requires member or
     * above on the import endpoints below.
     */
    @Operation(summary = "导出 Agent 记忆快照（ZIP）")
    @GetMapping(value = "/memory/export", produces = "application/zip")
    @RequireWorkspaceRole("viewer")
    public ResponseEntity<byte[]> exportMemory(
            @PathVariable Long agentId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            Authentication authentication) {
        byte[] body = memoryArchiveService.export(
                agentId, workspaceId, currentWebOwner(authentication));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"memory-agent-" + agentId + ".zip\"")
                .body(body);
    }

    /**
     * Dry-run an import: classify every entry as create / update (with old
     * vs new size + hash) / skip (with reason). Required so the UI can show
     * the diff before the user commits.
     */
    @Operation(summary = "预览导入 Agent 记忆快照（不写入）")
    @PostMapping(value = "/memory/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireWorkspaceRole("member")
    public R<WorkspaceMemoryArchiveService.ImportPreview> previewImportMemory(
            @PathVariable Long agentId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        return R.ok(memoryArchiveService.previewImport(
                agentId, workspaceId, readBytes(file), currentWebOwner(authentication)));
    }

    /**
     * Commit the import. Atomic — all whitelisted entries succeed or the
     * transaction rolls back. Out-of-whitelist entries are silently skipped
     * (their count is in the response payload).
     */
    @Operation(summary = "导入 Agent 记忆快照（写入）")
    @PostMapping(value = "/memory/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireWorkspaceRole("member")
    public R<WorkspaceMemoryArchiveService.ImportResult> importMemory(
            @PathVariable Long agentId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        return R.ok(memoryArchiveService.apply(
                agentId, workspaceId, readBytes(file), currentWebOwner(authentication)));
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new NewsClawException(400, "Missing or empty upload file");
        }
        // Pre-check on the compressed wire size — bomb defence in the service
        // catches "1 KB compressed → 1 GB decompressed" amplification, but
        // does nothing about a legitimate 100 MB compressed upload (Spring's
        // multipart.max-file-size allows that) materialising on heap before
        // we ever start decompressing. A real memory bundle compresses to a
        // few MB; rejecting > MAX_TOTAL_BYTES compressed loses nothing real.
        if (file.getSize() > WorkspaceMemoryArchiveService.MAX_TOTAL_BYTES) {
            throw new NewsClawException(400,
                    "Upload exceeds size limit (> " + WorkspaceMemoryArchiveService.MAX_TOTAL_BYTES + " bytes compressed)");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new NewsClawException(400, "Failed to read upload: " + e.getMessage());
        }
    }

    private static String currentWebOwner(Authentication authentication) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new NewsClawException(401, "Not authenticated");
        }
        return "user:" + authentication.getName();
    }

    private static boolean isMemoryManagedFilename(String filename) {
        return filename != null && ("MEMORY.md".equals(filename)
                || "PROFILE.md".equals(filename)
                || "SOUL.md".equals(filename)
                || filename.matches("memory/\\d{4}-\\d{2}-\\d{2}\\.md")
                || filename.matches("structured/(user|feedback|project|reference)\\.md"));
    }

    @Data
    static class SaveFileRequest {
        private String content;
    }

    @Data
    static class PromptFilesRequest {
        private List<String> files;
    }
}

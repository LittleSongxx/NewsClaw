package vip.newsclaw.memory.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import vip.newsclaw.common.result.R;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;
import vip.newsclaw.memory.MemoryProperties;
import vip.newsclaw.memory.service.*;
import vip.newsclaw.memory.scheduler.DreamingScheduler;
import vip.newsclaw.workspace.document.WorkspaceFileService;
import vip.newsclaw.workspace.document.model.WorkspaceFileEntity;
import vip.newsclaw.workspace.conversation.ConversationService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆管理接口
 * <p>
 * 提供记忆整合的手动触发和状态查询。
 *
 * @author NewsClaw Team
 */
@Tag(name = "记忆管理")
@Slf4j
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryEmergenceService emergenceService;
    private final MemorySummarizationService summarizationService;
    private final MemoryRecallService recallService;
    private final MemoryProperties memoryProperties;
    private final DreamingScheduler dreamingScheduler;
    private final WorkspaceFileService workspaceFileService;
    private final StructuredMemoryConsolidationService structuredConsolidationService;
    private ConversationService conversationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConversationService(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Operation(summary = "手动触发 always-on 结构化记忆整合（user/feedback，合并去重过时条目）")
    @PostMapping("/{agentId}/structured-consolidation")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> triggerStructuredConsolidation(@PathVariable Long agentId) {
        try {
            StructuredMemoryConsolidationService.ConsolidationStats s =
                    structuredConsolidationService.consolidateAgent(agentId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ownersConsolidated", s.ownersConsolidated);
            out.put("updated", s.updated);
            out.put("skippedSmall", s.skippedSmall);
            out.put("skippedOverCap", s.skippedOverCap);
            out.put("failed", s.failed);
            out.put("entriesBefore", s.entriesBefore);
            out.put("entriesAfter", s.entriesAfter);
            return R.ok(out);
        } catch (Exception e) {
            log.error("[Memory] Manual structured consolidation failed for agent={}: {}",
                    agentId, e.getMessage(), e);
            return R.fail("结构化记忆整合失败: " + e.getMessage());
        }
    }

    @Operation(summary = "手动触发记忆整合（daily notes → MEMORY.md，NIGHTLY 模式）")
    @PostMapping("/{agentId}/emergence")
    @RequireWorkspaceRole("member")
    public R<DreamReport> triggerEmergence(@PathVariable Long agentId,
                                           Authentication authentication) {
        try {
            DreamReport report = emergenceService.consolidate(
                    agentId, DreamMode.NIGHTLY, null, currentWebOwner(authentication));
            return R.ok(report);
        } catch (Exception e) {
            log.error("[Memory] Manual emergence failed for agent={}: {}", agentId, e.getMessage(), e);
            return R.fail("记忆整合失败: " + e.getMessage());
        }
    }

    @Operation(summary = "Focused Dream — 围绕指定主题触发记忆整合")
    @PostMapping("/{agentId}/dreaming/focused")
    @RequireWorkspaceRole("member")
    public R<DreamReport> triggerFocusedDream(@PathVariable Long agentId,
                                              @RequestBody Map<String, String> body,
                                              Authentication authentication) {
        if (!memoryProperties.getDream().isFocusedEnabled()) {
            return R.fail(410, "Focused dream is disabled");
        }
        String topic = body != null ? body.get("topic") : null;
        if (topic == null || topic.isBlank()) {
            return R.fail("topic is required");
        }
        try {
            DreamReport report = emergenceService.consolidate(
                    agentId, DreamMode.FOCUSED, topic, currentWebOwner(authentication));
            return R.ok(report);
        } catch (Exception e) {
            log.error("[Memory] Focused dream failed for agent={}: {}", agentId, e.getMessage(), e);
            return R.fail("Focused dream failed: " + e.getMessage());
        }
    }

    @Operation(summary = "手动触发对话记忆提取")
    @PostMapping("/{agentId}/summarize/{conversationId}")
    @RequireWorkspaceRole("member")
    public R<Map<String, String>> triggerSummarize(
            @PathVariable Long agentId,
            @PathVariable String conversationId,
            Authentication authentication) {
        if (conversationService != null && (authentication == null
                || !conversationService.isConversationOwner(conversationId, authentication.getName()))) {
            return R.fail(403, "conversation access denied");
        }
        try {
            summarizationService.analyzeAndUpdateMemory(
                    agentId, conversationId, currentWebOwner(authentication));
            return R.ok(Map.of("status", "completed"));
        } catch (Exception e) {
            log.error("[Memory] Manual summarization failed for agent={}, conv={}: {}",
                    agentId, conversationId, e.getMessage(), e);
            return R.fail("记忆提取失败: " + e.getMessage());
        }
    }

    // ==================== Dreaming 状态 API ====================

    @Operation(summary = "查询 Dreaming 状态（配置、统计、上次运行时间）")
    @GetMapping("/{agentId}/dreaming/status")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> getDreamingStatus(
            @PathVariable Long agentId,
            Authentication authentication) {
        Map<String, Object> status = recallService.getDreamingStatus(
                agentId, currentWebOwner(authentication));
        status.put("lastRunTime", dreamingScheduler.getLastRunTime());
        return R.ok(status);
    }

    @Operation(summary = "查询召回候选列表（含评分详情）")
    @GetMapping("/{agentId}/dreaming/candidates")
    @RequireWorkspaceRole("member")
    public R<List<Map<String, Object>>> getDreamingCandidates(
            @PathVariable Long agentId,
            Authentication authentication) {
        return R.ok(recallService.listCandidatesWithDetails(
                agentId, currentWebOwner(authentication)));
    }

    @Operation(summary = "查询 DREAMS.md 整合日记")
    @GetMapping("/{agentId}/dreaming/dreams")
    @RequireWorkspaceRole("member")
    public R<Map<String, Object>> getDreams(@PathVariable Long agentId,
                                            Authentication authentication) {
        WorkspaceFileEntity file = workspaceFileService.getVisibleFile(
                agentId, "DREAMS.md", currentWebOwner(authentication));
        Map<String, Object> result = new LinkedHashMap<>();
        if (file != null && file.getContent() != null) {
            result.put("content", file.getContent());
            result.put("updateTime", file.getUpdateTime());
        } else {
            result.put("content", null);
            result.put("message", "尚未生成 DREAMS.md（需先运行一次 emergence）");
        }
        return R.ok(result);
    }

    private String currentWebOwner(Authentication authentication) {
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new NewsClawException("err.auth.unauthenticated", 401, "Not authenticated");
        }
        return "user:" + authentication.getName();
    }
}

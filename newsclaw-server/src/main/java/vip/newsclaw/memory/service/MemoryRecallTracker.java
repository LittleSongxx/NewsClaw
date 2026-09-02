package vip.newsclaw.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import vip.newsclaw.workspace.document.model.WorkspaceFileEntity;
import vip.newsclaw.workspace.document.repository.WorkspaceFileMapper;
import vip.newsclaw.memory.identity.MemoryScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆召回追踪器
 * <p>
 * 在每次对话时异步记录哪些 workspace 文件/片段被实际注入到上下文中。
 * 追踪粒度为片段级（daily note 按 ## 标题拆分），而非文件级。
 *
 * @author NewsClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryRecallTracker {

    private final MemoryRecallService recallService;
    private final WorkspaceFileMapper workspaceFileMapper;

    /** 用于拆分 daily note 中的二级标题片段 */
    private static final Pattern SECTION_PATTERN = Pattern.compile("(?m)^## .+");

    /**
     * 异步追踪一次对话中被实际注入到 system prompt 的文件片段。
     * <p>
     * 仅追踪满足 buildSystemPrompt 注入条件的文件（enabled=true, content 非空）。
     * 对 daily note 类文件（memory/*.md），按 ## 标题拆分为独立片段追踪。
     *
     * @param agentId   Agent ID
     * @param userQuery 用户消息（用于计算 query hash）
     */
    @Async
    public void trackRecalls(Long agentId, String userQuery) {
        trackRecalls(agentId, userQuery, null);
    }

    /** Track only shared rows plus the current owner's PERSONAL rows. */
    @Async
    public void trackRecalls(Long agentId, String userQuery, String ownerKey) {
        try {
            LambdaQueryWrapper<WorkspaceFileEntity> q = new LambdaQueryWrapper<WorkspaceFileEntity>()
                    .eq(WorkspaceFileEntity::getAgentId, agentId)
                    .eq(WorkspaceFileEntity::getEnabled, true)
                    .isNotNull(WorkspaceFileEntity::getContent)
                    .ne(WorkspaceFileEntity::getContent, "");
            applyVisibility(q, ownerKey);
            List<WorkspaceFileEntity> injectedFiles = workspaceFileMapper.selectList(
                    q.orderByAsc(WorkspaceFileEntity::getSortOrder));

            if (injectedFiles.isEmpty()) {
                return;
            }

            String queryHash = sha256Short(userQuery);
            int trackedCount = 0;

            for (WorkspaceFileEntity file : injectedFiles) {
                if (!isVisible(file, ownerKey)) {
                    continue;
                }
                String content = file.getContent();
                if (content == null || content.isBlank()) {
                    continue;
                }

                String filename = file.getFilename();
                String scope = file.getScope() == null ? MemoryScope.TEAM : file.getScope();
                String fileOwner = MemoryScope.PERSONAL.equals(scope) ? file.getOwnerKey() : null;

                if (filename.startsWith("memory/") && filename.endsWith(".md")) {
                    // daily note: 按 ## 标题拆分为独立片段
                    trackedCount += trackDailyNoteSnippets(
                            agentId, filename, content, queryHash, fileOwner, scope);
                } else {
                    // 非 daily note (PROFILE.md, MEMORY.md 等): 文件级追踪
                    recallService.recordRecall(agentId, filename, content, queryHash, fileOwner, scope);
                    trackedCount++;
                }
            }

            log.debug("[MemoryRecall] Tracked {} snippets for agent={}", trackedCount, agentId);
        } catch (Exception e) {
            log.warn("[MemoryRecall] Failed to track recalls for agent={}: {}", agentId, e.getMessage());
        }
    }

    /**
     * 将 daily note 按 ## 标题拆分为独立片段，分别追踪
     */
    private int trackDailyNoteSnippets(Long agentId, String filename, String content, String queryHash,
                                       String ownerKey, String scope) {
        Matcher matcher = SECTION_PATTERN.matcher(content);
        List<Integer> sectionStarts = new java.util.ArrayList<>();
        while (matcher.find()) {
            sectionStarts.add(matcher.start());
        }

        if (sectionStarts.isEmpty()) {
            // 没有 ## 标题，整个文件作为一个片段
            recallService.recordRecall(agentId, filename, content.trim(), queryHash, ownerKey, scope);
            return 1;
        }

        int count = 0;
        // 如果 ## 前有内容，作为第一个片段
        if (sectionStarts.get(0) > 0) {
            String preamble = content.substring(0, sectionStarts.get(0)).trim();
            if (!preamble.isEmpty()) {
                recallService.recordRecall(
                        agentId, filename + "#preamble", preamble, queryHash, ownerKey, scope);
                count++;
            }
        }

        for (int i = 0; i < sectionStarts.size(); i++) {
            int start = sectionStarts.get(i);
            int end = (i + 1 < sectionStarts.size()) ? sectionStarts.get(i + 1) : content.length();
            String snippet = content.substring(start, end).trim();
            if (!snippet.isEmpty()) {
                // 从 ## 标题行提取 section 标识
                String firstLine = snippet.contains("\n") ? snippet.substring(0, snippet.indexOf('\n')).trim() : snippet;
                String sectionKey = filename + "#" + sanitizeSectionKey(firstLine);
                recallService.recordRecall(agentId, sectionKey, snippet, queryHash, ownerKey, scope);
                count++;
            }
        }
        return count;
    }

    /** Max length of a section slug — keeps the full key (path + '#' + slug)
     *  well under the mate_memory_recall.filename VARCHAR(256) ceiling even
     *  when an LLM writes an over-long H2 heading. CJK chars live in the BMP,
     *  so {@code substring} can never split a surrogate pair here. */
    static final int MAX_SECTION_SLUG = 200;

    /** Package-private for direct unit testing of the slug/truncation logic. */
    static String sanitizeSectionKey(String heading) {
        // "## Some Title" -> "some-title"
        String slug = heading.replaceFirst("^#+\\s*", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
                .replaceAll("^-|-$", "");
        return slug.length() > MAX_SECTION_SLUG ? slug.substring(0, MAX_SECTION_SLUG) : slug;
    }

    /**
     * 追踪 Agent 通过 WorkspaceMemoryTool 主动读取文件的信号。
     * 这是比被动注入更强的"真实需要"指标。
     * 使用固定 queryHash 以区分主动检索和被动注入。
     */
    @Async
    public void trackActiveRetrieval(Long agentId, String filename, String content) {
        trackActiveRetrieval(agentId, filename, content, null, MemoryScope.TEAM);
    }

    @Async
    public void trackActiveRetrieval(Long agentId, String filename, String content,
                                     String ownerKey, String scope) {
        try {
            if (agentId == null || filename == null || content == null || content.isBlank()) {
                return;
            }
            recallService.recordRecall(
                    agentId, filename, content, "__active_read__", ownerKey, scope);
            log.debug("[MemoryRecall] Tracked active retrieval: agent={}, file={}", agentId, filename);
        } catch (Exception e) {
            log.warn("[MemoryRecall] Failed to track active retrieval for agent={}: {}", agentId, e.getMessage());
        }
    }

    private static void applyVisibility(LambdaQueryWrapper<WorkspaceFileEntity> q, String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            q.in(WorkspaceFileEntity::getScope, MemoryScope.TEAM, MemoryScope.GLOBAL);
            return;
        }
        String owner = ownerKey.trim();
        q.and(w -> w.in(WorkspaceFileEntity::getScope, MemoryScope.TEAM, MemoryScope.GLOBAL)
                .or(p -> p.eq(WorkspaceFileEntity::getScope, MemoryScope.PERSONAL)
                        .eq(WorkspaceFileEntity::getOwnerKey, owner)));
    }

    static boolean isVisible(WorkspaceFileEntity file, String ownerKey) {
        if (file == null) return false;
        String scope = file.getScope() == null ? MemoryScope.TEAM : file.getScope();
        if (MemoryScope.isShared(scope)) return true;
        return MemoryScope.PERSONAL.equals(scope) && ownerKey != null
                && !ownerKey.isBlank() && ownerKey.trim().equals(file.getOwnerKey());
    }

    private String sha256Short(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8 && i < hash.length; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

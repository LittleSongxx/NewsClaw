package vip.newsclaw.memory.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import vip.newsclaw.memory.MemoryProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Admission control for durable memory.
 *
 * <p>Long-term memory is a product decision, not a free append-only cache.
 * Every writer first receives a {@link MemoryWriteDecision}; only after the
 * physical file write succeeds does it call {@link #markApplied(Long)}. This
 * leaves a queryable trail for rejected news bodies, budget pressure, stale
 * facts and competing versions without pretending filesystem writes are a
 * single database transaction.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryWriteGovernanceService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CONFLICTED = "CONFLICTED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private static final Set<String> MEMORY_TYPES = Set.of(
            "user", "feedback", "project", "reference", "freeform", "memory_document", "profile", "daily");

    private final MemoryWriteLedgerMapper ledgerMapper;
    private final MemoryProperties properties;

    @PostConstruct
    public void recoverPendingOnStartup() {
        rejectStalePending();
    }

    @Scheduled(fixedDelayString = "${newsclaw.memory.governance-pending-sweep-ms:300000}")
    public void sweepStalePending() {
        rejectStalePending();
    }

    private void rejectStalePending() {
        if (!properties.isGovernanceEnabled()) return;
        int ttl = Math.max(1, properties.getGovernancePendingTtlMinutes());
        int recovered = ledgerMapper.update(null,
                new LambdaUpdateWrapper<MemoryWriteLedgerEntity>()
                        .eq(MemoryWriteLedgerEntity::getStatus, STATUS_PENDING)
                        .lt(MemoryWriteLedgerEntity::getCreateTime,
                                java.time.LocalDateTime.now().minusMinutes(ttl))
                        .set(MemoryWriteLedgerEntity::getStatus, STATUS_REJECTED)
                        .set(MemoryWriteLedgerEntity::getRejectionReason,
                                "Writer did not confirm before pending TTL; recovered after interruption"));
        if (recovered > 0) {
            log.warn("[MemoryGovernance] Recovered {} stale PENDING ledger row(s)", recovered);
        }
    }

    /**
     * Create a pending ledger row only when the candidate is suitable for
     * durable memory. Callers must follow a positive decision with
     * {@link #markApplied(Long)} after the underlying file write succeeds.
     */
    @Transactional
    public MemoryWriteDecision admit(MemoryWriteRequest request) {
        if (!properties.isGovernanceEnabled()) {
            return MemoryWriteDecision.bypassed();
        }
        NormalizedRequest normalized = normalize(request);
        if (normalized.error() != null) {
            return reject(normalized, normalized.error());
        }
        if (properties.isRejectNewsBody() && looksLikeNewsEvidence(normalized)) {
            return reject(normalized, "新闻正文或证据原文应归档到 AI 动态事件/Wiki，不写入长期记忆");
        }
        int maxPerEntry = Math.max(1, properties.getWriteMaxTokens());
        if (normalized.tokenEstimate() > maxPerEntry) {
            return reject(normalized, "单条记忆超过 " + maxPerEntry + " token 预算");
        }

        List<MemoryWriteLedgerEntity> sameKey = findByKey(normalized);
        MemoryWriteLedgerEntity active = sameKey.stream()
                .filter(this::isActive)
                .max(Comparator.comparing(MemoryWriteLedgerEntity::getVersionNo,
                        Comparator.nullsFirst(Integer::compareTo)))
                .orElse(null);
        if (active != null && normalized.contentHash().equals(active.getContentHash())) {
            return reject(normalized, "相同内容已存在于长期记忆");
        }
        if (active != null && !maySupersede(normalized)) {
            return conflict(normalized, active, nextVersion(sameKey));
        }

        int tokenBudget = Math.max(0, properties.getLongTermTokenBudget());
        int used = activeTokenEstimate(normalized);
        int replacement = active == null || active.getTokenEstimate() == null ? 0 : active.getTokenEstimate();
        if (tokenBudget > 0 && used - replacement + normalized.tokenEstimate() > tokenBudget) {
            return reject(normalized, "长期记忆 token 预算已满（" + tokenBudget + "）");
        }

        MemoryWriteLedgerEntity row = row(normalized, STATUS_PENDING, null, nextVersion(sameKey));
        if (active != null) {
            row.setSupersedesId(active.getId());
        }
        ledgerMapper.insert(row);
        return MemoryWriteDecision.allowed(row.getId());
    }

    /** Confirm the durable-file operation. This is idempotent for retry safety. */
    @Transactional
    public void markApplied(Long ledgerId) {
        if (ledgerId == null || !properties.isGovernanceEnabled()) {
            return;
        }
        MemoryWriteLedgerEntity row = ledgerMapper.selectById(ledgerId);
        if (row == null || STATUS_APPLIED.equals(row.getStatus())) {
            return;
        }
        if (!STATUS_PENDING.equals(row.getStatus())) {
            throw new IllegalStateException("Memory ledger " + ledgerId + " is not pending");
        }
        if (row.getSupersedesId() != null) {
            ledgerMapper.update(null, new LambdaUpdateWrapper<MemoryWriteLedgerEntity>()
                    .eq(MemoryWriteLedgerEntity::getId, row.getSupersedesId())
                    .eq(MemoryWriteLedgerEntity::getWorkspaceId, row.getWorkspaceId())
                    .in(MemoryWriteLedgerEntity::getStatus, STATUS_APPLIED, STATUS_PENDING)
                    .set(MemoryWriteLedgerEntity::getStatus, STATUS_SUPERSEDED));
        }
        row.setStatus(STATUS_APPLIED);
        row.setRejectionReason(null);
        ledgerMapper.updateById(row);
    }

    /** Record an underlying file-write failure so a pending admission is not left ambiguous. */
    @Transactional
    public void markFailed(Long ledgerId, String reason) {
        if (ledgerId == null || !properties.isGovernanceEnabled()) {
            return;
        }
        ledgerMapper.update(null, new LambdaUpdateWrapper<MemoryWriteLedgerEntity>()
                .eq(MemoryWriteLedgerEntity::getId, ledgerId)
                .eq(MemoryWriteLedgerEntity::getStatus, STATUS_PENDING)
                .set(MemoryWriteLedgerEntity::getStatus, STATUS_REJECTED)
                .set(MemoryWriteLedgerEntity::getRejectionReason, trim(reason, 1000)));
    }

    private MemoryWriteDecision reject(NormalizedRequest request, String reason) {
        MemoryWriteLedgerEntity row = row(request, STATUS_REJECTED, reason, 1);
        ledgerMapper.insert(row);
        return MemoryWriteDecision.denied(row.getId(), STATUS_REJECTED, reason);
    }

    private MemoryWriteDecision conflict(NormalizedRequest request, MemoryWriteLedgerEntity active, int version) {
        MemoryWriteLedgerEntity row = row(request, STATUS_CONFLICTED,
                "同一记忆键已有不同内容，等待人工确认", version);
        row.setSupersedesId(active.getId());
        ledgerMapper.insert(row);
        return MemoryWriteDecision.denied(row.getId(), STATUS_CONFLICTED, row.getRejectionReason());
    }

    private MemoryWriteLedgerEntity row(NormalizedRequest request, String status, String reason, int version) {
        MemoryWriteLedgerEntity row = new MemoryWriteLedgerEntity();
        row.setWorkspaceId(request.workspaceId());
        row.setAgentId(request.agentId());
        row.setOwnerKey(request.ownerKey());
        row.setMemoryType(request.memoryType());
        row.setMemoryKey(request.memoryKey());
        row.setSource(request.source());
        row.setSourceConversationId(request.sourceConversationId());
        row.setSourceRef(request.sourceRef());
        row.setContentHash(request.contentHash());
        row.setContent(request.content());
        row.setTokenEstimate(request.tokenEstimate());
        row.setVersionNo(version);
        row.setStatus(status);
        row.setRejectionReason(trim(reason, 1000));
        row.setDeleted(0);
        return row;
    }

    private List<MemoryWriteLedgerEntity> findByKey(NormalizedRequest request) {
        LambdaQueryWrapper<MemoryWriteLedgerEntity> q = baseScope(request)
                .eq(MemoryWriteLedgerEntity::getMemoryType, request.memoryType())
                .eq(MemoryWriteLedgerEntity::getMemoryKey, request.memoryKey())
                .eq(MemoryWriteLedgerEntity::getDeleted, 0)
                .orderByDesc(MemoryWriteLedgerEntity::getVersionNo);
        return ledgerMapper.selectList(q);
    }

    private int activeTokenEstimate(NormalizedRequest request) {
        List<MemoryWriteLedgerEntity> rows = ledgerMapper.selectList(baseScope(request)
                .eq(MemoryWriteLedgerEntity::getDeleted, 0)
                .in(MemoryWriteLedgerEntity::getStatus, STATUS_APPLIED, STATUS_PENDING));
        return rows.stream().map(MemoryWriteLedgerEntity::getTokenEstimate)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
    }

    private LambdaQueryWrapper<MemoryWriteLedgerEntity> baseScope(NormalizedRequest request) {
        LambdaQueryWrapper<MemoryWriteLedgerEntity> q = new LambdaQueryWrapper<MemoryWriteLedgerEntity>()
                .eq(MemoryWriteLedgerEntity::getWorkspaceId, request.workspaceId())
                .eq(MemoryWriteLedgerEntity::getAgentId, request.agentId());
        if (request.ownerKey() == null) {
            q.isNull(MemoryWriteLedgerEntity::getOwnerKey);
        } else {
            q.eq(MemoryWriteLedgerEntity::getOwnerKey, request.ownerKey());
        }
        return q;
    }

    private boolean isActive(MemoryWriteLedgerEntity row) {
        return STATUS_APPLIED.equals(row.getStatus()) || STATUS_PENDING.equals(row.getStatus());
    }

    private boolean maySupersede(NormalizedRequest request) {
        return "human".equals(request.source())
                || "user-requested".equals(request.source());
    }

    private int nextVersion(List<MemoryWriteLedgerEntity> rows) {
        return rows.stream().map(MemoryWriteLedgerEntity::getVersionNo)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    private boolean looksLikeNewsEvidence(NormalizedRequest request) {
        String source = request.source().toLowerCase(Locale.ROOT);
        String ref = request.sourceRef() == null ? "" : request.sourceRef().toLowerCase(Locale.ROOT);
        String key = request.memoryKey().toLowerCase(Locale.ROOT);
        boolean newsContext = source.contains("news") || source.contains("evidence")
                || ref.contains("ai-news") || ref.contains("evidence") || ref.startsWith("wiki:")
                || key.contains("news") || key.contains("evidence") || key.contains("article");
        int lines = (int) request.content().chars().filter(ch -> ch == '\n').count();
        boolean articleShape = request.content().contains("http://") || request.content().contains("https://")
                || lines >= 6 || request.tokenEstimate() > 96;
        return newsContext && articleShape;
    }

    private NormalizedRequest normalize(MemoryWriteRequest request) {
        long workspaceId = request == null || request.workspaceId() == null || request.workspaceId() <= 0
                ? DEFAULT_WORKSPACE_ID : request.workspaceId();
        long agentId = request == null || request.agentId() == null ? 0L : request.agentId();
        String type = lower(request == null ? null : request.memoryType());
        String key = lower(request == null ? null : request.memoryKey());
        String content = request == null || request.content() == null ? "" : request.content().trim();
        String source = lower(request == null ? null : request.source());
        String sourceRef = trim(request == null ? null : request.sourceRef(), 1000);
        String sourceConversation = trim(request == null ? null : request.sourceConversationId(), 256);
        String ownerKey = trim(request == null ? null : request.ownerKey(), 128);
        String error = null;
        if (agentId <= 0) error = "缺少可信 Agent ID";
        else if (!MEMORY_TYPES.contains(type)) error = "不支持的记忆类型";
        else if (!key.matches("^[a-z0-9][a-z0-9._-]{0,255}$")) error = "记忆 key 必须是 1-256 位小写标识符";
        else if (content.isBlank()) error = "记忆内容不能为空";
        else if (source.isBlank()) error = "记忆来源不能为空";
        else if (properties.isRequireSourceRef() && (sourceRef == null || sourceRef.isBlank())) error = "长期记忆必须保留来源指针";
        String hash = sha256(content);
        return new NormalizedRequest(workspaceId, agentId, ownerKey, type, key, content, source,
                sourceConversation, sourceRef, hash, estimateTokens(content), error);
    }

    /** Conservative no-dependency estimate: CJK chars ~= tokens, other text ~= 4 chars/token. */
    static int estimateTokens(String content) {
        if (content == null || content.isBlank()) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.HANGUL_SYLLABLES) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.max(1, cjk + (other + 3) / 4);
    }

    private static String sha256(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record NormalizedRequest(Long workspaceId, Long agentId, String ownerKey, String memoryType,
                                     String memoryKey, String content, String source,
                                     String sourceConversationId, String sourceRef, String contentHash,
                                     int tokenEstimate, String error) {
    }
}

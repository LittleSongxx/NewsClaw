package vip.newsclaw.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import vip.newsclaw.content.model.ContentItemEntity;
import vip.newsclaw.content.repository.ContentItemMapper;
import vip.newsclaw.tool.document.GeneratedFileCache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content calendar / dedup ledger service — the single home for content-item
 * logic, shared by {@code content_item} (the tool), the package tools (which
 * auto-record on delivery), and the read-only content-calendar API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentItemService {

    /** Statuses that count as "already covered" for dedup — a discarded draft doesn't. */
    private static final List<String> COMMITTED_STATUSES = List.of("packaged", "operator_acknowledged", "published");
    /** Ignore same-topic rows created within this window, so a record→check in one
     *  run doesn't flag itself as a repeat. */
    private static final long SELF_MATCH_GUARD_MINUTES = 2;
    /** Re-packaging the same topic within this window updates the existing ledger
     *  row instead of inserting a duplicate (the agent may call a package tool twice). */
    private static final long RECORD_DEDUP_MINUTES = 10;

    private final ContentItemMapper contentItemMapper;

    /** Optional in narrow unit tests; production binds approvals to the cached artifact bytes. */
    @Autowired(required = false)
    private GeneratedFileCache generatedFileCache;

    /**
     * Recent committed items with the same topic fingerprint on this platform,
     * within {@code days}, excluding just-created rows (self-match guard). Empty
     * means "not a repeat".
     */
    public List<ContentItemEntity> findRecent(Long workspaceId, String platform, String topic, int days) {
        if (isBlank(platform) || isBlank(topic)) return List.of();
        LocalDateTime now = LocalDateTime.now();
        return contentItemMapper.selectList(new LambdaQueryWrapper<ContentItemEntity>()
                .eq(ContentItemEntity::getWorkspaceId, normalizeWorkspace(workspaceId))
                .eq(ContentItemEntity::getDeleted, 0)
                .eq(ContentItemEntity::getPlatform, platform.trim().toLowerCase())
                .eq(ContentItemEntity::getTopicFingerprint, fingerprint(topic))
                .in(ContentItemEntity::getStatus, COMMITTED_STATUSES)
                .ge(ContentItemEntity::getCreateTime, now.minusDays(days))
                .lt(ContentItemEntity::getCreateTime, now.minusMinutes(SELF_MATCH_GUARD_MINUTES))
                .orderByDesc(ContentItemEntity::getCreateTime));
    }

    /** Backward-compatible single-user overload for older callers. */
    public List<ContentItemEntity> findRecent(String platform, String topic, int days) {
        return findRecent(1L, platform, topic, days);
    }

    /**
     * Record a produced piece; returns its item id. Idempotent within a short
     * window: re-packaging the same topic on the same platform (e.g. the agent
     * called a package tool twice) updates the existing row instead of inserting
     * a duplicate. A published row is never overwritten.
     */
    public Long record(Long workspaceId, String platform, String topic, String title,
                       String status, String previewUrl, String externalRef) {
        if (isBlank(platform) || isBlank(topic)) {
            throw new IllegalArgumentException("platform and topic are required");
        }
        String plat = platform.trim().toLowerCase();
        String fp = fingerprint(topic != null ? topic : title);
        String resolvedStatus = status == null || status.isBlank() ? "packaged" : status.trim().toLowerCase();
        if (!List.of("draft", "packaged", "failed").contains(resolvedStatus)) {
            throw new IllegalArgumentException("unsupported content lifecycle status");
        }
        if ("published".equals(resolvedStatus) || "operator_acknowledged".equals(resolvedStatus)) {
            throw new IllegalArgumentException("delivery status must pass the explicit acknowledgement gate");
        }

        ContentItemEntity existing = contentItemMapper.selectOne(new LambdaQueryWrapper<ContentItemEntity>()
                .eq(ContentItemEntity::getWorkspaceId, normalizeWorkspace(workspaceId))
                .eq(ContentItemEntity::getDeleted, 0)
                .eq(ContentItemEntity::getPlatform, plat)
                .eq(ContentItemEntity::getTopicFingerprint, fp)
                .ne(ContentItemEntity::getStatus, "published")
                .ge(ContentItemEntity::getCreateTime, LocalDateTime.now().minusMinutes(RECORD_DEDUP_MINUTES))
                .orderByDesc(ContentItemEntity::getCreateTime)
                .last("LIMIT 1"));
        if (existing != null) {
            boolean protectedDelivery = "operator_acknowledged".equalsIgnoreCase(existing.getStatus())
                    || "published".equalsIgnoreCase(existing.getStatus());
            if (!protectedDelivery) {
                if (title != null && !title.isBlank()) {
                    existing.setTitle(title.trim());
                }
                if (previewUrl != null) {
                    existing.setPreviewUrl(previewUrl);
                }
                if (externalRef != null) {
                    existing.setExternalRef(externalRef);
                }
                existing.setStatus(resolvedStatus);
                contentItemMapper.updateById(existing);
            }
            log.info("[ContentItem] re-package dedup: updated id={} platform={} topic='{}'",
                    existing.getId(), plat, topic);
            return existing.getId();
        }

        ContentItemEntity e = new ContentItemEntity();
        e.setWorkspaceId(normalizeWorkspace(workspaceId));
        e.setPlatform(plat);
        e.setTopic(topic != null ? topic.trim() : null);
        e.setTopicFingerprint(fp);
        e.setTitle(title != null ? title.trim() : null);
        e.setStatus(resolvedStatus);
        e.setPreviewUrl(previewUrl);
        e.setExternalRef(externalRef);
        contentItemMapper.insert(e);
        log.info("[ContentItem] recorded id={} ws={} platform={} status={} title='{}'",
                e.getId(), workspaceId, e.getPlatform(), e.getStatus(), title);
        return e.getId();
    }

    /** Flip an item to published. Returns false if the id is unknown. */
    public boolean markPublished(Long workspaceId, Long id, String externalRef) {
        if (isBlank(externalRef)) return false;
        ContentItemEntity e = contentItemMapper.selectOne(new LambdaQueryWrapper<ContentItemEntity>()
                .eq(ContentItemEntity::getId, id)
                .eq(ContentItemEntity::getWorkspaceId, normalizeWorkspace(workspaceId))
                .eq(ContentItemEntity::getDeleted, 0));
        if (e == null) {
            return false;
        }
        if ("published".equalsIgnoreCase(e.getStatus())) {
            return !isBlank(e.getExternalRef()) && !isBlank(e.getArtifactHash());
        }
        if (!"operator_acknowledged".equalsIgnoreCase(e.getStatus())
                || isBlank(e.getArtifactHash())) {
            return false;
        }
        if (generatedFileCache != null
                && !artifactMatches(e, normalizeWorkspace(workspaceId), e.getArtifactHash())) {
            return false;
        }
        e.setStatus("published");
        e.setPublishTime(LocalDateTime.now());
        e.setExternalRef(externalRef.trim());
        e.setPlatformPublishedAt(LocalDateTime.now());
        contentItemMapper.updateById(e);
        log.info("[ContentItem] item {} marked published (ref={})", id, externalRef);
        return true;
    }

    /** Record a human approval without claiming that a platform accepted the item. */
    public boolean acknowledge(Long workspaceId, Long id, String artifactHash) {
        if (isBlank(artifactHash) || !artifactHash.trim().matches("(?i)[0-9a-f]{64}")) return false;
        ContentItemEntity e = contentItemMapper.selectOne(new LambdaQueryWrapper<ContentItemEntity>()
                .eq(ContentItemEntity::getId, id)
                .eq(ContentItemEntity::getWorkspaceId, normalizeWorkspace(workspaceId))
                .eq(ContentItemEntity::getDeleted, 0));
        if (e == null || (!"packaged".equalsIgnoreCase(e.getStatus())
                && !"operator_acknowledged".equalsIgnoreCase(e.getStatus()))) return false;
        if (generatedFileCache != null && !artifactMatches(e, normalizeWorkspace(workspaceId), artifactHash)) {
            return false;
        }
        e.setStatus("operator_acknowledged");
        e.setArtifactHash(artifactHash.trim().toLowerCase());
        e.setOperatorAcknowledgedAt(LocalDateTime.now());
        contentItemMapper.updateById(e);
        return true;
    }

    /** Backward-compatible single-user overload for older callers. */
    public boolean markPublished(Long id, String externalRef) {
        return markPublished(1L, id, externalRef);
    }

    /** Paged content-calendar listing, newest first, optionally filtered by platform / status. */
    public IPage<ContentItemEntity> page(Long workspaceId, int page, int size, String platform, String status) {
        LambdaQueryWrapper<ContentItemEntity> w = new LambdaQueryWrapper<>();
        w.eq(ContentItemEntity::getWorkspaceId, normalizeWorkspace(workspaceId));
        w.eq(ContentItemEntity::getDeleted, 0);
        if (platform != null && !platform.isBlank()) {
            w.eq(ContentItemEntity::getPlatform, platform.trim().toLowerCase());
        }
        if (status != null && !status.isBlank()) {
            w.eq(ContentItemEntity::getStatus, status.trim().toLowerCase());
        }
        w.orderByDesc(ContentItemEntity::getCreateTime);
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        return contentItemMapper.selectPage(new Page<>(p, s), w);
    }

    /** Backward-compatible single-user overload for older callers. */
    public IPage<ContentItemEntity> page(int page, int size, String platform, String status) {
        return page(1L, page, size, platform, status);
    }

    /** Counts by status (draft/packaged/published/failed) plus total, for the summary cards. */
    public Map<String, Long> summary(Long workspaceId) {
        Map<String, Long> m = new LinkedHashMap<>();
        long ws = normalizeWorkspace(workspaceId);
        for (String s : List.of("draft", "packaged", "operator_acknowledged", "published", "failed")) {
            m.put(s, contentItemMapper.selectCount(
                            new LambdaQueryWrapper<ContentItemEntity>()
                            .eq(ContentItemEntity::getWorkspaceId, ws)
                            .eq(ContentItemEntity::getDeleted, 0)
                            .eq(ContentItemEntity::getStatus, s)));
        }
        m.put("total", contentItemMapper.selectCount(
                new LambdaQueryWrapper<ContentItemEntity>()
                        .eq(ContentItemEntity::getWorkspaceId, ws)
                        .eq(ContentItemEntity::getDeleted, 0)));
        return m;
    }

    /** Backward-compatible single-user overload for older callers. */
    public Map<String, Long> summary() {
        return summary(1L);
    }

    private static long normalizeWorkspace(Long workspaceId) {
        return workspaceId == null || workspaceId <= 0 ? 1L : workspaceId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean artifactMatches(ContentItemEntity item, long workspaceId, String suppliedHash) {
        if (item.getPreviewUrl() == null) return false;
        java.util.regex.Matcher matcher = GeneratedFileCache.GENERATED_URL_PATTERN.matcher(item.getPreviewUrl());
        if (!matcher.find()) return false;
        return generatedFileCache.getForWorkspace(matcher.group(1), workspaceId)
                .map(entry -> sha256(entry.bytes()).equalsIgnoreCase(suppliedHash.trim()))
                .orElse(false);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format("%02x", value));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Stable 32-hex fingerprint of the normalized topic (lowercased, alnum/CJK only). */
    public static String fingerprint(String topic) {
        String normalized = topic == null ? "" : topic.toLowerCase()
                .replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+", "");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }
}

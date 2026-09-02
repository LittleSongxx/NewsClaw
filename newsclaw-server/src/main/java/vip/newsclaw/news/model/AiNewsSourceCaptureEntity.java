package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable source snapshot created before an Agent may attach evidence. */
@Data
@TableName("mate_ai_news_source_capture")
public class AiNewsSourceCaptureEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String sourceUrl;
    private String sourceUrlHash;
    private String finalUrl;
    private String sourceTitle;
    /** UTC local timestamp parsed only from explicit timezone-bearing metadata. */
    private LocalDateTime sourcePublishedAt;
    private String publishedAtRaw;
    private String publishedAtMethod;
    /** NONE, PAGE_METADATA, or STRUCTURED_SOURCE. */
    private String sourceTimeOrigin;
    /** Audit outcome for the optional structured-source time bridge. */
    private String sourceTimeAttestationStatus;
    private Long sourceTimeItemVersionId;
    private String sourceTimeAttestationHash;
    private String sourceTier;
    private Integer httpStatus;
    private LocalDateTime fetchedAt;
    private String contentHash;
    private String contentType;
    private String captureMethod;
    private String redirectChainJson;
    /** Normalized bounded source text used for exact quote location. */
    private String extractedText;
    /** SHA-256 of extractedText, checked again before every evidence binding. */
    private String extractedTextHash;
    private Integer textLength;
    /** Main-content implementation and immutable configuration provenance. */
    private String extractorName;
    private String extractorVersion;
    private String extractorConfigHash;
    /** 1 only for an explicitly recorded compatibility fallback. */
    private Integer extractionFallback;
    private String extractionWarning;
    private String captureStatus;
    private String captureError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}

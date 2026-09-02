package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Queryable candidate identity; provider/query provenance lives in observations. */
@Data
@TableName("mate_ai_news_candidate")
public class AiNewsCandidateEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    /** Scan whose discovery decision and capture/review state this row owns. */
    private Long scanRunId;
    private String canonicalUrl;
    private String canonicalUrlHash;
    private String originalUrl;
    private String title;
    private String snippet;
    private String providerId;
    private String queryLane;
    private Integer providerRank;
    private String sourceKey;
    private String sourceClass;
    private String publishedAtHint;
    private String timeConfidence;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private String acquisitionStatus;
    private String selectionStatus;
    private String captureStatus;
    private String normalizationStatus;
    private String reviewStatus;
    private BigDecimal selectionScore;
    private String selectionReason;
    private Long captureId;
    /** Event created by the explicit promotion bridge; null means candidate-only. */
    private Long eventId;
    private LocalDateTime promotedAt;
    private Integer captureAttempts;
    private LocalDateTime captureStartedAt;
    private LocalDateTime nextCaptureAt;
    private Long storyId;
    private String rejectReason;
    private String failureReason;
    private String reviewReason;
    /** Authenticated adjudication provenance; promotion requires HUMAN origin. */
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewOrigin;
    private String configVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}

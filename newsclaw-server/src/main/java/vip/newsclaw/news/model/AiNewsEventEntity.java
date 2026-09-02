package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** Persisted, workspace-scoped AI industry event. JSON fields retain claims and entity labels. */
@Data
@TableName("mate_ai_news_event")
public class AiNewsEventEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String eventKey;
    private String title;
    private String summary;
    private String category;
    private String entitiesJson;
    private String status;
    private Double confidence;
    /** Deterministic evidence-quality score used for candidate ordering. */
    private Double rankingScore;
    private String claimsJson;
    private String conflictsJson;
    private LocalDateTime discoveredAt;
    /** Earliest publication time parsed from a server-captured source page. */
    private LocalDateTime sourcePublishedAt;
    /** Editorial delivery acknowledgement time; not a source/event timestamp. */
    private LocalDateTime publishedAt;
    private Long wikiPageId;
    /**
     * Read-only navigation projection populated on the detail endpoint. The
     * event domain intentionally stores only the page id; Wiki owns the
     * knowledge-base/slug relationship and remains the source of truth.
     */
    @TableField(exist = false)
    private Long wikiKbId;
    @TableField(exist = false)
    private String wikiSlug;
    private Long teamRunId;
    private Long gzhContentItemId;
    private Long xhsContentItemId;
    /** Delivery state is separate from source/event lifecycle status. */
    private String deliveryStatus;
    private LocalDateTime operatorAcknowledgedAt;
    private LocalDateTime platformPublishedAt;
    private String platformExternalRef;
    private String artifactHash;
    /**
     * List-view evidence projection. Evidence remains a separate aggregate;
     * this prevents the workbench from inferring verification from event state.
     */
    @TableField(exist = false)
    private Integer evidenceCount;
    @TableField(exist = false)
    private Integer verifiedEvidenceCount;
    @TableField(exist = false)
    private String primaryEvidenceTier;
    /** Current versioned event-cluster projection; membership history stays in V210 tables. */
    @TableField(exist = false)
    private Long clusterId;
    @TableField(exist = false)
    private Long clusterVersionId;
    @TableField(exist = false)
    private Integer clusterMemberCount;
    @TableField(exist = false)
    private String clusterAssignmentOrigin;
    @TableField(exist = false)
    private Double clusterAssignmentScore;
    @TableField(exist = false)
    private Boolean clusterReviewRequired;
    /** Persisted review-queue projection; no model-provided boolean controls this field. */
    @TableField(exist = false)
    private Boolean reviewRequired;
    @TableField(exist = false)
    private Long reviewTaskId;
    @TableField(exist = false)
    private String reviewStatus;
    @TableField(exist = false)
    private List<String> reviewReasons;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private Integer deleted;
}

package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persisted source of truth for a high-risk AI-news review decision. Channel
 * cards are delivery attempts only; they never replace this queue row.
 */
@Data
@TableName("mate_ai_news_review_task")
public class AiNewsReviewTaskEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long eventId;
    private String status;
    private String reasonsJson;
    private String policyVersion;
    /** SHA-256 of policy-relevant evidence/capture inputs when the task was decided. */
    private String riskFingerprint;
    private String routeSource;
    private LocalDateTime cardIssuedAt;
    private String cardDeliveryError;
    private LocalDateTime resolvedAt;
    private String resolvedBy;
    private String resolutionNote;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private Integer deleted;
}

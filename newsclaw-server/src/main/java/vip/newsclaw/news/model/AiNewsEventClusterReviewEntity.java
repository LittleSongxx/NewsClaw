package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable low-confidence merge proposal produced by the online clusterer. */
@Data
@TableName("mate_ai_news_event_cluster_review")
public class AiNewsEventClusterReviewEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long eventId;
    private Long sourceClusterId;
    private Long candidateClusterId;
    private String proposedAction;
    private Double score;
    private Double decisionThreshold;
    private String algorithmName;
    private String algorithmVersion;
    private String featureVersion;
    private String configHash;
    private String scoreBreakdownJson;
    private String status;
    private String reviewer;
    private String reviewNote;
    private LocalDateTime resolvedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private Integer deleted;
}

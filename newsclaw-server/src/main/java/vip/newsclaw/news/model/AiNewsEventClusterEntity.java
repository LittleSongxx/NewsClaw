package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Stable cluster identity whose membership is represented by immutable versions. */
@Data
@TableName("mate_ai_news_event_cluster")
public class AiNewsEventClusterEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String clusterKey;
    private String status;
    private Long currentVersionId;
    private String createdOrigin;
    @TableField(exist = false)
    private Integer versionNo;
    @TableField(exist = false)
    private Long representativeEventId;
    @TableField(exist = false)
    private String canonicalTitle;
    @TableField(exist = false)
    private String category;
    @TableField(exist = false)
    private Integer memberCount;
    @TableField(exist = false)
    private String algorithmVersion;
    @TableField(exist = false)
    private String configHash;
    @TableField(exist = false)
    private Integer pendingReviewCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private Integer deleted;
}

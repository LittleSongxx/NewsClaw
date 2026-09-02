package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable metadata snapshot for one event-cluster membership version. */
@Data
@TableName("mate_ai_news_event_cluster_version")
public class AiNewsEventClusterVersionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long clusterId;
    private Integer versionNo;
    private String changeType;
    private Long representativeEventId;
    private String canonicalTitle;
    private String category;
    private String entitiesJson;
    private LocalDateTime earliestSourcePublishedAt;
    private LocalDateTime latestSourcePublishedAt;
    private Integer memberCount;
    private String algorithmName;
    private String algorithmVersion;
    private String featureVersion;
    private String configHash;
    private String changeReason;
    private String createdBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    private Integer deleted;
}

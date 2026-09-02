package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Directed edge recording a manual merge or split without deleting history. */
@Data
@TableName("mate_ai_news_event_cluster_lineage")
public class AiNewsEventClusterLineageEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String operationId;
    private String operationType;
    private Long fromClusterId;
    private Long fromVersionId;
    private Long toClusterId;
    private Long toVersionId;
    private String reason;
    private String reviewer;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    private Integer deleted;
}

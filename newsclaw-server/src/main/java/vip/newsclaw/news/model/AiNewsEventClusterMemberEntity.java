package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One event's membership in one immutable cluster version. */
@Data
@TableName("mate_ai_news_event_cluster_member")
public class AiNewsEventClusterMemberEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long clusterId;
    private Long clusterVersionId;
    private Long eventId;
    private Double membershipScore;
    private String assignmentOrigin;
    private String scoreBreakdownJson;
    private LocalDateTime assignedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    private Integer deleted;
}

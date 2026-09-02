package vip.newsclaw.team.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Comment on a team task, written by an agent, a human, or the system.
 * A comment of type "blocker" auto-fails the task and escalates to the lead.
 *
 * @author NewsClaw Team
 */
@Data
@TableName("mate_team_task_comment")
public class TeamTaskCommentEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("task_id")
    private Long taskId;

    /** Denormalized team id for board-level queries. */
    @TableField("team_id")
    private Long teamId;

    /** Author kind: agent / user / system. */
    @TableField("author_type")
    private String authorType;

    /** Agent id or username depending on authorType. */
    @TableField("author_id")
    private String authorId;

    /** note / blocker. */
    @TableField("comment_type")
    private String commentType;

    private String content;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

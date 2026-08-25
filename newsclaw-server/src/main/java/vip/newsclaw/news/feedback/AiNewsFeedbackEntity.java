package vip.newsclaw.news.feedback;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable human/badcase feedback for the AI-news production loop. */
@Data
@TableName("mate_ai_news_feedback")
public class AiNewsFeedbackEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String feedbackHash;
    private Long eventId;
    private Long teamRunId;
    private Long taskId;
    private String feedbackType;
    private String note;
    private String evidenceJson;
    private String skillName;
    private String proposalAction;
    private Long proposalId;
    private String status;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updateTime;
    private Integer deleted;
}

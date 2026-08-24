package vip.mate.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Auditable capture attempt, including failures that must not become evidence rows. */
@Data
@TableName("mate_ai_news_capture_attempt")
public class AiNewsCaptureAttemptEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long eventId;
    private Long workspaceId;
    private String sourceUrl;
    private String finalUrl;
    private String captureStatus;
    private String captureError;
    private Integer httpStatus;
    private String captureMethod;
    private String redirectChainJson;
    private LocalDateTime attemptedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}

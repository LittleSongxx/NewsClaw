package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One immutable-in-intent poll/push activity with mutable terminal counters. */
@Data
@TableName("mate_ai_news_ingestion_run")
public class AiNewsIngestionRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long endpointId;
    private String providerId;
    private String channel;
    private String triggerType;
    private String traceId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String runStatus;
    private Integer httpStatus;
    private Boolean notModified;
    private Integer transportCount;
    private Integer itemCount;
    private Integer newItemCount;
    private Integer newVersionCount;
    private Integer unchangedItemCount;
    private Long bytesReceived;
    private Integer retryCount;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}

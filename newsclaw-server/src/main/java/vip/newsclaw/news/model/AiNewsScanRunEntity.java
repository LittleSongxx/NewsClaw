package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One backend-owned candidate-pipeline scan, independent from Agent execution. */
@Data
@TableName("mate_ai_news_scan_run")
public class AiNewsScanRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String triggerType;
    private String topic;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private String runStatus;
    private String configVersion;
    private String idempotencyKey;
    private Integer activeSlot;
    private Long discoveryRunId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer providerCount;
    private Integer providerDisabledCount;
    private Integer rawResultCount;
    private Integer invalidResultCount;
    private Integer uniqueCandidateCount;
    private Integer selectedCandidateCount;
    private Integer captureSuccessCount;
    private Integer captureFailureCount;
    private Integer reviewedCount;
    private Integer acceptedCount;
    private String errorMessage;
    /** Loaded only for run inspection, not list pages. */
    @TableField(select = false)
    private String summaryJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}

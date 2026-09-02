package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Transport response record; raw bytes are retained only when endpoint policy permits it. */
@Data
@TableName("mate_ai_news_raw_capture")
public class AiNewsRawCaptureEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long ingestionRunId;
    private Long endpointId;
    private String requestUrl;
    private String requestUrlHash;
    private Integer attemptNo;
    private String finalUrl;
    private Integer httpStatus;
    private String contentType;
    private String etag;
    private String lastModified;
    private String retryAfter;
    private Long declaredContentLength;
    private Long receivedBytes;
    private String representationDigest;
    private String retentionApplied;
    private String bodyObjectKey;
    private byte[] rawBody;
    private Boolean truncated;
    private Boolean notModified;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String errorCode;
    private String errorMessage;
    private Long revalidatedFromCaptureId;
    private LocalDateTime createTime;
    private Integer deleted;
}

package vip.newsclaw.news.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Read projection that intentionally never loads or returns retained raw bytes. */
@Data
public class AiNewsRawCaptureMetadataRow {
    private Long id;
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
    private Boolean bodyRetained;
    private Boolean truncated;
    private Boolean notModified;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String errorCode;
    private String errorMessage;
    private Long revalidatedFromCaptureId;
}

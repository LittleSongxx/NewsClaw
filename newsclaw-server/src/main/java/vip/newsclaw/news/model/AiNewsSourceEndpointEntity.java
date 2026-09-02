package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persistent configuration and polling cursor for one structured source endpoint. */
@Data
@TableName("mate_ai_news_source_endpoint")
public class AiNewsSourceEndpointEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String endpointKey;
    private Integer catalogVersion;
    private String sourceKey;
    private String providerId;
    private String channel;
    private String adapter;
    private String endpointUrl;
    private String endpointUrlHash;
    private Boolean enabled;
    private String languagesJson;
    private String categoriesJson;
    private Integer pollIntervalSeconds;
    private Boolean evidenceEligible;
    private String rightsStatus;
    private String rawRetention;
    private String robotsStatus;
    private String etag;
    private String lastModified;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime nextPollAt;
    private Integer consecutiveFailures;
    private Integer lastHttpStatus;
    private String lastError;
    private String configFingerprint;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}

package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Stable identity of a publisher item; content changes live in version rows. */
@Data
@TableName("mate_ai_news_source_item")
public class AiNewsSourceItemEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long endpointId;
    private String identityHash;
    private String externalItemId;
    private String canonicalUrl;
    private String canonicalUrlHash;
    private String sourceUrl;
    private String sourceTier;
    private LocalDateTime firstObservedAt;
    private LocalDateTime lastObservedAt;
    private Long latestVersionId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}

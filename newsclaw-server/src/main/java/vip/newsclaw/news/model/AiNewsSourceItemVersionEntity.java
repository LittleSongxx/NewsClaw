package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Semantic version of a normalized source item, deduplicated by version hash. */
@Data
@TableName("mate_ai_news_source_item_version")
public class AiNewsSourceItemVersionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long sourceItemId;
    private Long ingestionRunId;
    private String versionHash;
    private String title;
    private String snippet;
    private String content;
    private LocalDateTime sourcePublishedAt;
    private String publishedAtRaw;
    private LocalDateTime sourceModifiedAt;
    private String modifiedAtRaw;
    private String language;
    private String provenanceJson;
    private LocalDateTime observedAt;
    private LocalDateTime createTime;
    private Integer deleted;
}

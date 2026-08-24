package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One source/claim packet attached to an AI news event. */
@Data
@TableName("mate_ai_news_event_evidence")
public class AiNewsEvidenceEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long eventId;
    private Long workspaceId;
    private String sourceUrl;
    /** SHA-256 of the canonical URL; keeps MySQL unique indexes below InnoDB's key limit. */
    private String sourceUrlHash;
    private String sourceTitle;
    private LocalDateTime sourcePublishedAt;
    private String sourceTier;
    private String claim;
    private String quote;
    private Double confidence;
    private Boolean verified;
    /** URL after a read-only official-source fetch follows safe redirects. */
    private String finalUrl;
    private LocalDateTime fetchedAt;
    /** SHA-256 of the bounded captured response body. */
    private String contentHash;
    private Integer httpStatus;
    /** e.g. READ_ONLY_HTTP; never a browser action or authenticated session. */
    private String captureMethod;
    private String redirectChainJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private Integer deleted;
}

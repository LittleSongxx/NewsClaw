package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One immutable provider/query/rank observation of a candidate URL. */
@Data
@TableName("mate_ai_news_candidate_observation")
public class AiNewsCandidateObservationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long candidateId;
    private Long scanRunId;
    private String providerId;
    private String queryLane;
    private Integer providerRank;
    private String originalUrl;
    private String observedUrlHash;
    private String title;
    private String snippet;
    private String publishedAtHint;
    private BigDecimal providerScore;
    private Boolean selected;
    private String selectionReason;
    private LocalDateTime observedAt;
    private LocalDateTime createTime;
    private Integer deleted;
}

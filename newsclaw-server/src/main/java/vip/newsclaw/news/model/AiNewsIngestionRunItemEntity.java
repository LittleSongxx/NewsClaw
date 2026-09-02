package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Observation edge preserving exactly which item version appeared in one run. */
@Data
@TableName("mate_ai_news_ingestion_run_item")
public class AiNewsIngestionRunItemEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long ingestionRunId;
    private Long sourceItemId;
    private Long sourceItemVersionId;
    private String observationOutcome;
    private LocalDateTime observedAt;
    private LocalDateTime createTime;
    private Integer deleted;
}

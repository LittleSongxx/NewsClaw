package vip.newsclaw.news.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One immutable discovery observation and its content-addressed replay payload. */
@Data
@TableName("mate_ai_news_discovery_run")
public class AiNewsDiscoveryRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String topic;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private LocalDateTime observedAt;
    private Integer requestedMaxCandidates;
    private Integer queryCount;
    private Integer cachedQueryCount;
    private Integer successfulQueryCount;
    private Integer uniqueUrlCount;
    private Integer selectedCandidateCount;
    private Integer structuredSourceCount;
    private String rankingPolicyVersion;
    private String snapshotHash;
    private String rankingHash;
    /** Omitted from list queries; inspect endpoints load it explicitly. */
    @TableField(select = false)
    private String snapshotJson;
    private LocalDateTime createTime;
    private Integer deleted;
}

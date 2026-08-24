package vip.mate.llm.trace;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One persisted model-selection or invocation outcome for an agent phase. */
@Data
@TableName("mate_llm_routing_trace")
public class LlmRoutingTraceEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long agentId;
    private String conversationId;
    private String phase;
    private String routeRole;
    private String providerId;
    private String modelName;
    private Integer attemptNo;
    private Integer fallbackOrdinal;
    private String outcome;
    private String failureCategory;
    private Long durationMs;
    private String metadataJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    private Integer deleted;
}

package vip.mate.dashboard.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("mate_usage_daily")
public class UsageDailyEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long agentId;
    private LocalDate statDate;
    private Integer conversationCount;
    private Integer messageCount;
    private Long totalTokens;
    private Long promptTokens;
    private Long completionTokens;
    /** RFC-014: Anthropic cache_read_input_tokens 累计 */
    private Long cacheReadTokens;
    /** RFC-014: Anthropic cache_creation_input_tokens 累计 */
    private Long cacheWriteTokens;
    private Integer toolCallCount;
    private Integer errorCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

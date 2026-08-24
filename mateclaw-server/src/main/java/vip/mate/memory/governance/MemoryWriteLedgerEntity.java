package vip.mate.memory.governance;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable decision trail for a proposed long-term memory write. */
@Data
@TableName("mate_memory_write_ledger")
public class MemoryWriteLedgerEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private Long agentId;
    @TableField(value = "owner_key", updateStrategy = FieldStrategy.ALWAYS)
    private String ownerKey;
    private String memoryType;
    private String memoryKey;
    private String source;
    @TableField(value = "source_conversation_id", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceConversationId;
    @TableField(value = "source_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceRef;
    private String contentHash;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String content;
    private Integer tokenEstimate;
    private Integer versionNo;
    @TableField(value = "supersedes_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long supersedesId;
    private String status;
    @TableField(value = "rejection_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String rejectionReason;
    @TableField(value = "resolved_by", updateStrategy = FieldStrategy.ALWAYS)
    private String resolvedBy;
    @TableField(value = "resolved_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime resolvedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private Integer deleted;
}

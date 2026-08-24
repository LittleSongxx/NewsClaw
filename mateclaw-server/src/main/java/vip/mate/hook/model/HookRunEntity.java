package vip.mate.hook.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Hook 触发审计（RFC-017）。对应 {@code mate_hook_run}。 */
@Data
@TableName("mate_hook_run")
public class HookRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long hookId;
    private String eventType;
    /** {@code vip.mate.hook.action.HookResult.Status#name()} */
    private String status;
    private Integer durationMs;
    private String message;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

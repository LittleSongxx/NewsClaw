package vip.mate.tool.guard.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具安全配置实体（单行配置表）
 */
@Data
@TableName("mate_tool_guard_config")
public class ToolGuardConfigEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Boolean enabled;
    private String guardScope;
    private String guardedToolsJson;
    private String deniedToolsJson;
    private Boolean fileGuardEnabled;
    private String sensitivePathsJson;

    /** 审计日志总开关 */
    private Boolean auditEnabled;
    /** 最低记录等级（INFO/LOW/MEDIUM/HIGH/CRITICAL） */
    private String auditMinSeverity;
    /** 审计日志保留天数（0=永不清理） */
    private Integer auditRetentionDays;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

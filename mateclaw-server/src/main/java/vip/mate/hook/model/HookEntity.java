package vip.mate.hook.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import vip.mate.hook.action.HookAction;

import java.time.LocalDateTime;

/**
 * Hook 定义（RFC-017）。
 *
 * <p>对应 {@code mate_hook} 表。{@code match_expression} 与 {@code action_config}
 * 都是 JSON 文本，运行时由 {@code HookRegistry} 反序列化为 {@link HookAction}。</p>
 */
@Data
@TableName("mate_hook")
public class HookEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;
    private String description;
    private Boolean enabled;

    /** 形如 {@code agent:end} / {@code tool:error}；支持通配如 {@code tool:*}。 */
    private String eventType;

    /** JSON；可选过滤表达式。M3 只实现简单 equals / regex 匹配，后续扩展 SpEL。 */
    private String matchExpression;

    /** {@link HookAction.Kind#name()}。 */
    private String actionKind;

    /** JSON；内容因 actionKind 不同（BuiltinAction 为 {op,arg}，HttpAction 为 {method,url,body}）。 */
    private String actionConfig;

    private Integer rateLimitPerMin;
    private Integer timeoutMs;

    /** db 或 file（YAML 加载）。 */
    private String source;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

package vip.mate.external.effect;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable outbox-style record for a single external side effect. */
@Data
@TableName("mate_external_effect")
public class ExternalEffectEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String effectType;
    private String idempotencyKey;
    private String aggregateType;
    private String aggregateId;
    private String target;
    private String requestDigest;
    private String requestJson;
    private String status;
    private String responseJson;
    private String errorMessage;
    private Integer attemptCount;
    private String ownerToken;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private Integer deleted;
}

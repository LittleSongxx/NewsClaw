package vip.mate.skill.proposal;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Reviewable, workspace-scoped candidate mutation for the Skill registry. */
@Data
@TableName("mate_skill_change_proposal")
public class SkillChangeProposalEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String proposalHash;
    private Long agentId;
    private String sourceType;
    private String sourceConversationId;
    private Long sourceRunId;
    private String action;
    private String skillName;
    private String beforeContent;
    private String afterContent;
    private String diffText;
    private String evidenceJson;
    private String riskLevel;
    private String status;
    private String reviewer;
    private String reviewNote;
    private Long snapshotId;
    private Long appliedSkillId;
    private String appliedVersion;
    private String rollbackStatus;
    private LocalDateTime reviewedAt;
    private LocalDateTime appliedAt;
    private LocalDateTime rolledBackAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private Integer deleted;
}

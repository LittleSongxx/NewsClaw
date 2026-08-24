package vip.mate.skill.runtime;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能安全验证结果
 * 包含扫描发现列表、整体严重级别、是否阻断
 */
@Data
@Builder
public class SkillValidationResult {

    /** 技能名称 */
    private String skillName;

    /** 是否通过安全扫描（无 CRITICAL/HIGH 发现） */
    private boolean passed;

    /** 是否被阻断（不允许进入 active set） */
    private boolean blocked;

    /** 最高严重级别 */
    private Severity maxSeverity;

    /** 扫描发现列表 */
    @Builder.Default
    private List<Finding> findings = new ArrayList<>();

    /** 警告列表（非阻断性） */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /** 摘要信息 */
    private String summary;

    // ==================== 内部模型 ====================

    public enum Severity {
        INFO, LOW, MEDIUM, HIGH, CRITICAL;

        public boolean isBlockLevel() {
            return this == CRITICAL || this == HIGH;
        }
    }

    /**
     * 单条扫描发现
     */
    @Data
    @Builder
    public static class Finding {
        private String ruleId;
        private Severity severity;
        private String category;
        private String title;
        private String description;
        private String filePath;
        private Integer lineNumber;
        private String snippet;
        private String remediation;
    }

    // ==================== 工厂方法 ====================

    public static SkillValidationResult pass(String skillName) {
        return SkillValidationResult.builder()
            .skillName(skillName)
            .passed(true)
            .blocked(false)
            .maxSeverity(Severity.INFO)
            .summary("Security scan passed")
            .build();
    }

    public static SkillValidationResult warn(String skillName, List<Finding> findings, List<String> warnings) {
        Severity max = findings.stream()
            .map(Finding::getSeverity)
            .max(Enum::compareTo)
            .orElse(Severity.INFO);
        return SkillValidationResult.builder()
            .skillName(skillName)
            .passed(true)
            .blocked(false)
            .maxSeverity(max)
            .findings(findings)
            .warnings(warnings)
            .summary(findings.size() + " finding(s), " + warnings.size() + " warning(s)")
            .build();
    }

    public static SkillValidationResult block(String skillName, List<Finding> findings, List<String> warnings) {
        Severity max = findings.stream()
            .map(Finding::getSeverity)
            .max(Enum::compareTo)
            .orElse(Severity.CRITICAL);
        return SkillValidationResult.builder()
            .skillName(skillName)
            .passed(false)
            .blocked(true)
            .maxSeverity(max)
            .findings(findings)
            .warnings(warnings)
            .summary("Blocked: " + findings.size() + " security issue(s) found")
            .build();
    }
}

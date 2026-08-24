package vip.mate.skill.runtime;

import vip.mate.skill.runtime.model.ResolvedSkill;

/**
 * 技能运行时策略接口
 * 扩展点：控制技能的激活、文件访问、脚本执行等策略
 */
public interface SkillRuntimePolicy {

    /**
     * 是否允许激活该技能
     */
    default boolean canActivate(ResolvedSkill skill) {
        return true;
    }

    /**
     * 是否允许读取指定文件
     */
    default boolean canReadFile(ResolvedSkill skill, String relativePath) {
        return true;
    }

    /**
     * 是否允许执行指定脚本
     */
    default boolean canExecuteScript(ResolvedSkill skill, String scriptPath) {
        return true;
    }
}

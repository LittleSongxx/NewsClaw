package vip.mate.hook;

import vip.mate.hook.action.HookAction;
import vip.mate.hook.model.HookEntity;

/**
 * 由 {@code HookRegistry} 按事件类型检索出的已装配 hook。
 *
 * @param entity 数据库侧定义
 * @param action 已反序列化并校验通过的 action 实例（复用，不要每次重建）
 */
public record HookMatch(HookEntity entity, HookAction action) { }

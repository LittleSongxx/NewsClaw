package vip.mate.hook.action;

import java.util.Map;

/**
 * 派发器传给 action 的只读上下文。
 *
 * @param hookId       当前 hook 的持久化主键
 * @param hookName     hook 名（便于日志）
 * @param templateVars 从事件 payload 抽出来的模板变量（供 action 做 {@code {{event.toolName}}} 替换）
 */
public record HookContext(Long hookId, String hookName, Map<String, Object> templateVars) {

    public HookContext {
        templateVars = (templateVars == null) ? Map.of() : Map.copyOf(templateVars);
    }
}

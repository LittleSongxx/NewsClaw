package vip.mate.tool.guard;

/**
 * 工具安全守卫接口
 * 在工具执行前进行安全检查，拦截危险操作
 */
public interface ToolGuard {

    /**
     * 检查工具调用是否安全
     *
     * @param toolName  工具名称
     * @param arguments 工具参数（JSON 字符串）
     * @return 检查结果
     */
    ToolGuardResult check(String toolName, String arguments);
}

package vip.mate.tool.guard;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 危险操作匹配模式
 *
 * @param regex    正则表达式
 * @param category 威胁类别（如 filesystem_destroy, sql_destroy）
 * @param reason   拦截原因说明
 */
public record DangerousPattern(
        String regex,
        String category,
        String reason
) {

    private static final Map<String, Pattern> COMPILED_CACHE = new ConcurrentHashMap<>();

    /**
     * 检查输入是否匹配该危险模式
     */
    public boolean matches(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        Pattern p = COMPILED_CACHE.computeIfAbsent(regex,
                r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE));
        return p.matcher(input).find();
    }
}

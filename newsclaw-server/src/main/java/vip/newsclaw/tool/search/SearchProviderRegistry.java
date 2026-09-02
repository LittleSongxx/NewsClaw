package vip.newsclaw.tool.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.newsclaw.system.model.SystemSettingsDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 搜索提供商注册表 — 收集所有 {@link SearchProvider} 实现，提供优先级排序与自动探测
 *
 * <p>provider 自动探测机制：
 * <ol>
 *   <li>用户显式配置的 primary provider → 直接使用</li>
 *   <li>按 autoDetectOrder 遍历，优先选有 credential 的 provider</li>
 *   <li>如果没有有 credential 的 provider，回退到第一个可用的 keyless provider</li>
 * </ol>
 *
 * @author NewsClaw Team
 */
@Slf4j
@Component
public class SearchProviderRegistry {

    private final List<SearchProvider> sortedProviders;
    private final Map<String, SearchProvider> providerMap;

    /** 插件注册的 provider（运行时可变），与 Spring 注入的内置 provider 合并成完整视图 */
    private final ConcurrentHashMap<String, SearchProvider> pluginProviders = new ConcurrentHashMap<>();

    /**
     * 注册写锁：大小写不敏感的冲突检测是"先检查后插入"，两个并发注册大小写变体
     * （"Foo"/"foo"）可能双双通过检查后各自落入不同 key——写路径必须原子化。
     * 读路径（getById/allSorted/resolve）仍走无锁的 ConcurrentHashMap。
     */
    private final Object registrationLock = new Object();

    public SearchProviderRegistry(List<SearchProvider> providers) {
        this.sortedProviders = providers.stream()
                .sorted(Comparator.comparingInt(SearchProvider::autoDetectOrder))
                .toList();
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(SearchProvider::id, Function.identity()));
        log.info("注册搜索提供商 {} 个: {}", sortedProviders.size(),
                sortedProviders.stream().map(p -> p.id() + "(order=" + p.autoDetectOrder() + ")").toList());
    }

    /**
     * 注册一个插件提供的 provider。
     *
     * <p>id 规则：不允许为空或含首尾空白（拒绝而非 trim——注册键必须与
     * {@code provider.id()} 完全一致，反注册才能对得上）；存储保留原始大小写，
     * 但存取统一大小写不敏感，防止环境变量中的 "Serper" 变体被错误地当成
     * 不存在的 provider。
     *
     * @throws IllegalArgumentException id 为空、含首尾空白，或与内置/已注册插件 provider 冲突
     */
    public void registerPluginProvider(SearchProvider provider) {
        String id = provider.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Search provider id must not be blank");
        }
        if (!id.equals(id.trim())) {
            throw new IllegalArgumentException(
                    "Search provider id must not contain leading/trailing whitespace: '" + id + "'");
        }
        synchronized (registrationLock) {
            if (containsIgnoreCase(providerMap.keySet(), id)) {
                throw new IllegalArgumentException(
                        "Search provider id conflicts with a built-in provider: " + id);
            }
            if (containsIgnoreCase(pluginProviders.keySet(), id)) {
                throw new IllegalArgumentException(
                        "Search provider id already registered by another plugin: " + id);
            }
            pluginProviders.put(id, provider);
        }
        log.info("插件搜索提供商已注册: {} (order={})", id, provider.autoDetectOrder());
    }

    private static boolean containsIgnoreCase(Set<String> ids, String candidate) {
        return ids.stream().anyMatch(existing -> existing.equalsIgnoreCase(candidate));
    }

    /** 反注册插件 provider（disable / rollback 路径调用；id 不存在时静默） */
    public void unregisterPluginProvider(String id) {
        String storedId = findKeyIgnoreCase(pluginProviders, id);
        if (storedId != null && pluginProviders.remove(storedId) != null) {
            log.info("插件搜索提供商已反注册: {}", storedId);
        }
    }

    /** 判断某个 id 是否由插件注册（而非内置 Spring bean） */
    public boolean isPluginProvider(String id) {
        return findIgnoreCase(pluginProviders, id) != null;
    }

    /** 按 ID 获取指定 provider（内置优先，其次插件注册区） */
    public SearchProvider getById(String id) {
        SearchProvider builtin = findIgnoreCase(providerMap, id);
        return builtin != null ? builtin : findIgnoreCase(pluginProviders, id);
    }

    /**
     * 获取按 autoDetectOrder 排序的全部 provider（内置 + 插件）。
     * <p>有插件注册时每次调用重新合并排序——provider 总数 &lt;10，无需缓存。
     */
    public List<SearchProvider> allSorted() {
        if (pluginProviders.isEmpty()) {
            return sortedProviders;
        }
        List<SearchProvider> merged = new ArrayList<>(sortedProviders);
        merged.addAll(pluginProviders.values());
        merged.sort(Comparator.comparingInt(SearchProvider::autoDetectOrder));
        return merged;
    }

    /**
     * 根据当前配置，解析应使用的 provider。
     *
     * <p>解析策略：
     * <ol>
     *   <li>如果用户配置了 primary provider 且该 provider 可用 → 选中</li>
     *   <li>否则按 autoDetectOrder 遍历，跳过 keyless，先找有 credential 的</li>
     *   <li>如果没找到 → 回退到第一个可用的 keyless provider</li>
     * </ol>
     *
     * @return 选中的 provider，或 null（完全无可用 provider）
     */
    public ResolvedProvider resolve(SystemSettingsDTO config) {
        // 1. 用户显式配置的 primary provider
        String configuredId = config.getSearchProvider();
        if (configuredId != null && !configuredId.isBlank()) {
            SearchProvider configured = getById(configuredId);
            if (configured != null && isAvailable(configured, config)) {
                return new ResolvedProvider(configured, "configured");
            }
        }

        // 2. 按优先级遍历，先找有 credential 的
        SearchProvider keylessFallback = null;
        for (SearchProvider p : allSorted()) {
            if (!p.requiresCredential()) {
                // 记住第一个可用的 keyless provider
                if (keylessFallback == null && isAvailable(p, config)) {
                    keylessFallback = p;
                }
                continue;
            }
            if (isAvailable(p, config)) {
                return new ResolvedProvider(p, "auto-detect");
            }
        }

        // 3. 回退到 keyless
        if (keylessFallback != null) {
            return new ResolvedProvider(keylessFallback, "keyless-fallback");
        }

        return null;
    }

    private boolean isAvailable(SearchProvider provider, SystemSettingsDTO config) {
        try {
            return provider.isAvailable(config);
        } catch (RuntimeException error) {
            log.warn("搜索 provider 可用性检查失败，已跳过 provider={}: {}",
                    provider.id(), error.getMessage());
            return false;
        }
    }

    /**
     * 解析结果
     */
    public record ResolvedProvider(SearchProvider provider, String source) {
    }

    private static <T> T findIgnoreCase(Map<String, T> values, String id) {
        if (id == null || id.isBlank() || values == null || values.isEmpty()) return null;
        T direct = values.get(id);
        if (direct != null) return direct;
        return values.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(id))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static String findKeyIgnoreCase(Map<String, ?> values, String id) {
        if (id == null || id.isBlank() || values == null || values.isEmpty()) return null;
        if (values.containsKey(id)) return id;
        return values.keySet().stream()
                .filter(key -> key != null && key.equalsIgnoreCase(id))
                .findFirst().orElse(null);
    }
}

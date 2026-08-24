package vip.mate.hook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.hook.model.HookEntity;
import vip.mate.hook.repository.HookMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hook 注册表：按 {@code event_type} 建立 O(1) 索引，在派发器热路径被读。
 *
 * <p>加载来源 M3 只支持 DB；YAML 文件加载放 M3.x。索引结构：
 * <pre>
 * event_type ──► List&lt;HookMatch&gt;（预装配的 action 实例）
 * </pre>
 * 支持完全匹配 + 前缀通配（例 {@code tool:*} 匹配所有 tool:xxx 事件）。</p>
 *
 * <p>索引在启动时一次性构建；运行时修改通过 {@link #reload()} 重建（UI CRUD 后调用）。
 * 读路径完全无锁（{@link ConcurrentHashMap} 的 get 只读）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HookRegistry {

    private final HookMapper hookMapper;
    private final HookActionFactory actionFactory;

    /** 精确匹配索引：event_type → matches。 */
    private final ConcurrentHashMap<String, List<HookMatch>> exactIndex = new ConcurrentHashMap<>();

    /** 通配索引：domain（agent / tool / ...）→ matches（当条目 event_type 形如 'tool:*'）。 */
    private final ConcurrentHashMap<String, List<HookMatch>> wildcardIndex = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        reload();
    }

    /** Spring 上下文完成后再次 reload，确保 Flyway 迁移先行。 */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        reload();
    }

    public synchronized void reload() {
        var wrapper = new LambdaQueryWrapper<HookEntity>()
                .eq(HookEntity::getEnabled, true);
        List<HookEntity> all;
        try {
            all = hookMapper.selectList(wrapper);
        } catch (Exception e) {
            log.warn("hook registry reload skipped (table not ready?): {}", e.getMessage());
            return;
        }
        var nextExact = new ConcurrentHashMap<String, List<HookMatch>>();
        var nextWild  = new ConcurrentHashMap<String, List<HookMatch>>();
        int ok = 0, bad = 0;
        for (HookEntity e : all) {
            try {
                var match = new HookMatch(e, actionFactory.build(e));
                String type = e.getEventType();
                if (type.endsWith(":*")) {
                    String domain = type.substring(0, type.length() - 2);
                    nextWild.computeIfAbsent(domain, k -> new java.util.ArrayList<>()).add(match);
                } else {
                    nextExact.computeIfAbsent(type, k -> new java.util.ArrayList<>()).add(match);
                }
                ok++;
            } catch (Exception ex) {
                log.warn("skip invalid hook id={} name={}: {}", e.getId(), e.getName(), ex.getMessage());
                bad++;
            }
        }
        this.exactIndex.clear();
        this.exactIndex.putAll(nextExact);
        this.wildcardIndex.clear();
        this.wildcardIndex.putAll(nextWild);
        log.info("hook registry loaded: {} hooks ({} skipped)", ok, bad);
    }

    /** 查匹配 hook。O(1) + 小列表线性合并。 */
    public List<HookMatch> match(String eventType) {
        var exact = exactIndex.get(eventType);
        int colon = eventType.indexOf(':');
        String domain = (colon > 0) ? eventType.substring(0, colon) : eventType;
        var wild = wildcardIndex.get(domain);
        if (exact == null && wild == null) return List.of();
        if (exact == null) return wild;
        if (wild == null) return exact;
        var combined = new java.util.ArrayList<HookMatch>(exact.size() + wild.size());
        combined.addAll(exact);
        combined.addAll(wild);
        return combined;
    }

    /** 仅测试 / 观察用。 */
    public Map<String, List<HookMatch>> exactIndexSnapshot() {
        return Map.copyOf(exactIndex);
    }
}

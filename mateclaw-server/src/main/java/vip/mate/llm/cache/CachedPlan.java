package vip.mate.llm.cache;

import java.util.List;
import java.util.SequencedCollection;

/**
 * 一次请求的缓存方案：要打哪些断点，TTL 多长。
 *
 * <p>使用 JDK 21 {@link SequencedCollection} 保证断点的固定顺序（首尾稳定，
 * 序列化器能直接按顺序写入），同时是不可变 record，便于在多线程间传递。</p>
 *
 * @param breakpoints 断点序列；调用方应当按顺序遍历
 * @param ttl         缓存 TTL；{@code null} 等价 {@link CacheTtl#DEFAULT_5M}
 */
public record CachedPlan(SequencedCollection<Breakpoint> breakpoints, CacheTtl ttl) {

    public CachedPlan {
        if (breakpoints == null) {
            throw new IllegalArgumentException("breakpoints must not be null");
        }
    }

    /** 空方案：不打任何断点（等价 NoOp）。 */
    public static CachedPlan none() {
        return new CachedPlan(List.of(), CacheTtl.DEFAULT_5M);
    }

    public boolean isEmpty() {
        return breakpoints.isEmpty();
    }

    public int size() {
        return breakpoints.size();
    }
}

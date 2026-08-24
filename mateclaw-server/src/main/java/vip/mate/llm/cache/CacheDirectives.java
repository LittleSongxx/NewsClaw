package vip.mate.llm.cache;

import java.util.List;
import java.util.Map;

/**
 * 协议无关的"缓存指令包"。
 *
 * <p>序列化器从 {@link CachedPlan} 推导出本对象后，由具体的请求拦截层
 * （如 {@code CachingChatModelDecorator} 注册到 RestClient 的 {@code ClientHttpRequestInterceptor}）
 * 把指令落到具体协议的请求体上。</p>
 *
 * @param breakpoints  最终要应用的断点（顺序敏感）
 * @param httpHeaders  需要附加到 HTTP 请求的额外 header（如 Anthropic extended-cache-ttl beta header）
 * @param ttl          TTL 提示
 * @param protocol     生产此指令的协议；运行时拦截器据此选分支
 */
public record CacheDirectives(
        List<Breakpoint> breakpoints,
        Map<String, String> httpHeaders,
        CacheTtl ttl,
        vip.mate.llm.model.ModelProtocol protocol) {

    public CacheDirectives {
        breakpoints = (breakpoints == null) ? List.of() : List.copyOf(breakpoints);
        httpHeaders = (httpHeaders == null) ? Map.of() : Map.copyOf(httpHeaders);
        if (ttl == null)      ttl = CacheTtl.DEFAULT_5M;
        if (protocol == null) throw new IllegalArgumentException("protocol must not be null");
    }

    public static CacheDirectives empty(vip.mate.llm.model.ModelProtocol protocol) {
        return new CacheDirectives(List.of(), Map.of(), CacheTtl.DEFAULT_5M, protocol);
    }

    public boolean isEmpty() {
        return breakpoints.isEmpty();
    }
}

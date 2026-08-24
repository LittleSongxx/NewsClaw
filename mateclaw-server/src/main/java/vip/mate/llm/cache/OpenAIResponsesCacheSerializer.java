package vip.mate.llm.cache;

import vip.mate.llm.model.ModelProtocol;

import java.util.Map;

/**
 * OpenAI Responses API 的 cache_control 序列化器。
 *
 * <p>OpenAI Responses 协议使用与 Anthropic 同形的 {@code cache_control} 字段挂在 input 数组的
 * content block 上，无需额外 HTTP header。M2 阶段保留断点信息，由
 * {@code ChatGPTResponsesClient} 在 buildRequestBody 时按指令落字段（M3 完成）。</p>
 */
public final class OpenAIResponsesCacheSerializer implements CacheSerializer {

    @Override
    public ModelProtocol protocol() {
        return ModelProtocol.OPENAI_CHATGPT;
    }

    @Override
    public CacheDirectives serialize(CachedPlan plan, CachePlanContext ctx) {
        if (plan == null || plan.isEmpty()) {
            return CacheDirectives.empty(ModelProtocol.OPENAI_CHATGPT);
        }
        // Responses API 没有 TTL 概念，全部按默认；headers 留空
        return new CacheDirectives(
                plan.breakpoints().stream().toList(),
                Map.of(),
                CacheTtl.DEFAULT_5M,
                ModelProtocol.OPENAI_CHATGPT);
    }
}

package vip.newsclaw.agent.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.newsclaw.channel.web.ChatStreamTracker;
import vip.newsclaw.llm.failover.FallbackEntry;
import vip.newsclaw.llm.failover.ProviderHealthProperties;
import vip.newsclaw.llm.failover.ProviderHealthTracker;
import vip.newsclaw.llm.trace.LlmRoutingTraceService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** End-to-end unit coverage for the configured Qwen primary -> DeepSeek fallback trace. */
class NodeStreamingChatHelperRoutingTraceTest {

    @Test
    @DisplayName("Qwen 主模型失败后 DeepSeek 备用模型成功，两段结果都写入 routing trace")
    void qwenToDeepSeekFallbackIsTraced() {
        ChatStreamTracker tracker = mock(ChatStreamTracker.class);
        when(tracker.isStopRequested(any())).thenReturn(false);
        ChatModel primary = failingModel(new RuntimeException("401 Unauthorized"));
        ChatModel fallback = successfulModel("DeepSeek recovered the AI-news run");
        LlmRoutingTraceService traces = mock(LlmRoutingTraceService.class);
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(
                tracker,
                List.of(new FallbackEntry("deepseek", fallback, "deepseek-v4-flash")),
                null,
                new ProviderHealthTracker(new ProviderHealthProperties()),
                "bailian-team");
        helper.setRoutingTraceContext(traces, 3L, "qwen3.7-plus");

        NodeStreamingChatHelper.StreamResult result = helper.streamCall(primary,
                new Prompt(List.of(new UserMessage("核验今天的 AI 动态"))), "conv-ai-news", "reasoning");

        assertEquals("DeepSeek recovered the AI-news run", result.text());
        ArgumentCaptor<LlmRoutingTraceService.RoutingTrace> captor =
                ArgumentCaptor.forClass(LlmRoutingTraceService.RoutingTrace.class);
        verify(traces, times(2)).record(captor.capture());
        List<LlmRoutingTraceService.RoutingTrace> records = captor.getAllValues();
        assertTrue(records.stream().anyMatch(trace -> "PRIMARY".equals(trace.routeRole())
                && "bailian-team".equals(trace.providerId())
                && "qwen3.7-plus".equals(trace.modelName())
                && "FAILED".equals(trace.outcome())
                && "AUTH_ERROR".equals(trace.failureCategory())));
        assertTrue(records.stream().anyMatch(trace -> "FALLBACK".equals(trace.routeRole())
                && "deepseek".equals(trace.providerId())
                && "deepseek-v4-flash".equals(trace.modelName())
                && "SUCCEEDED".equals(trace.outcome())
                && trace.fallbackOrdinal() == 1));
    }

    private static ChatModel successfulModel(String content) {
        ChatModel model = mock(ChatModel.class);
        Generation generation = new Generation(new AssistantMessage(content), ChatGenerationMetadata.NULL);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResults()).thenReturn(List.of(generation));
        when(response.getResult()).thenReturn(generation);
        when(response.getMetadata()).thenReturn(null);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        return model;
    }

    private static ChatModel failingModel(Throwable failure) {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(failure));
        return model;
    }
}

package vip.newsclaw.llm.chatmodel;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import vip.newsclaw.llm.model.ModelProviderEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class BailianForcedToolChoiceCompatibilityTest {

    @Test
    void forcedFunctionDisablesThinkingOnlyForBailianQwen() {
        Object functionChoice = OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.function("ai_news_event");
        OpenAiApi.ChatCompletionRequest request = request("qwen3.7-plus", functionChoice,
                Map.of("trace_marker", "preserved", "enable_thinking", true));

        OpenAiApi.ChatCompletionRequest rewritten =
                OpenAiRequestRewriter.disableBailianQwenThinkingForForcedToolChoice(
                        request, provider("bailian-team"));

        assertFalse((Boolean) rewritten.extraBody().get("enable_thinking"));
        assertEquals("preserved", rewritten.extraBody().get("trace_marker"));
        assertEquals(functionChoice, rewritten.toolChoice());
    }

    @Test
    void autoNoneAndUnrelatedRoutesRemainUntouched() {
        OpenAiApi.ChatCompletionRequest none = request("qwen3.7-plus", "none", null);
        OpenAiApi.ChatCompletionRequest auto = request("qwen3.7-plus", "auto", null);
        OpenAiApi.ChatCompletionRequest required = request("qwen3.7-plus", "required", null);
        OpenAiApi.ChatCompletionRequest nonQwen = request("deepseek-v3.2", "required", null);

        assertSame(none, OpenAiRequestRewriter.disableBailianQwenThinkingForForcedToolChoice(
                none, provider("bailian-team")));
        assertSame(auto, OpenAiRequestRewriter.disableBailianQwenThinkingForForcedToolChoice(
                auto, provider("bailian-team")));
        assertSame(required, OpenAiRequestRewriter.disableBailianQwenThinkingForForcedToolChoice(
                required, provider("deepseek")));
        assertSame(nonQwen, OpenAiRequestRewriter.disableBailianQwenThinkingForForcedToolChoice(
                nonQwen, provider("bailian-team")));
    }

    private static ModelProviderEntity provider(String id) {
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId(id);
        return provider;
    }

    private static OpenAiApi.ChatCompletionRequest request(String model, Object toolChoice,
                                                            Map<String, Object> extraBody) {
        return new OpenAiApi.ChatCompletionRequest(
                List.of(), model, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, List.of(), toolChoice, null,
                null, null, null, null, null, null, extraBody);
    }
}
